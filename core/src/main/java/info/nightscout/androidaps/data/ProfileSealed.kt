package info.nightscout.androidaps.data

import info.nightscout.androidaps.core.R
import info.nightscout.androidaps.database.data.Block
import info.nightscout.androidaps.database.data.TargetBlock
import info.nightscout.androidaps.database.embedments.InsulinConfiguration
import info.nightscout.androidaps.database.embedments.InterfaceIDs
import info.nightscout.androidaps.database.entities.EffectiveProfileSwitch
import info.nightscout.androidaps.database.entities.ProfileSwitch
import info.nightscout.androidaps.extensions.*
import info.nightscout.androidaps.interfaces.Config
import info.nightscout.androidaps.interfaces.GlucoseUnit
import info.nightscout.androidaps.interfaces.Profile
import info.nightscout.androidaps.interfaces.Profile.Companion.secondsFromMidnight
import info.nightscout.androidaps.interfaces.Profile.Companion.toMgdl
import info.nightscout.androidaps.interfaces.Profile.ProfileValue
import info.nightscout.androidaps.interfaces.Pump
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.HardLimits
import info.nightscout.androidaps.utils.Round
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.resources.ResourceHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.util.*

sealed class ProfileSealed(
    val id: Long,
    val isValid: Boolean,
    val interfaceIDs_backing: InterfaceIDs?,
    val timestamp: Long,
    var basalBlocks: List<Block>,
    var isfBlocks: List<Block>,
    var icBlocks: List<Block>,
    var targetBlocks: List<TargetBlock>,
    val profileName: String,
    var duration: Long?, // [milliseconds]
    var ts: Int, // timeshift [hours]
    var pct: Int,
    var insulinConfiguration: InsulinConfiguration,
    val utcOffset: Long
) : Profile {

    data class PS(val value: ProfileSwitch) : ProfileSealed(
        value.id,
        value.isValid,
        value.interfaceIDs_backing,
        value.timestamp,
        value.basalBlocks,
        value.isfBlocks,
        value.icBlocks,
        value.targetBlocks,
        value.profileName,
        value.duration,
        T.msecs(value.timeshift).hours().toInt(),
        value.percentage,
        value.insulinConfiguration,
        value.utcOffset
    )

    data class EPS(val value: EffectiveProfileSwitch) : ProfileSealed(
        value.id,
        value.isValid,
        value.interfaceIDs_backing,
        value.timestamp,
        value.basalBlocks,
        value.isfBlocks,
        value.icBlocks,
        value.targetBlocks,
        value.originalProfileName,
        null, // already converted to non customized
        0, // already converted to non customized
        100, // already converted to non customized
        value.insulinConfiguration,
        value.utcOffset
    )

    data class Pure(val value: PureProfile) : ProfileSealed(
        0,
        true,
        null,
        0,
        value.basalBlocks,
        value.isfBlocks,
        value.icBlocks,
        value.targetBlocks,
        "",
        null,
        0,
        100,
        InsulinConfiguration("", (value.dia * 3600 * 1000).toLong(), 0),
        value.timeZone.rawOffset.toLong()
    )

    override fun isValid(from: String, pump: Pump, config: Config, resourceHelper: ResourceHelper, rxBus: RxBusWrapper, hardLimits: HardLimits): Profile.ValidityCheck {
        val notify = true
        val validityCheck = Profile.ValidityCheck()
        val description = pump.pumpDescription
        for (basal in basalBlocks) {
            val basalAmount = basal.amount * percentage / 100.0
            if (!description.is30minBasalRatesCapable) {
                // Check for hours alignment
                val duration: Long = basal.duration
                if (duration % 3600000 != 0L) {
                    if (notify && config.APS) {
                        val notification = Notification(
                            Notification.BASAL_PROFILE_NOT_ALIGNED_TO_HOURS,
                            resourceHelper.gs(R.string.basalprofilenotaligned, from),
                            Notification.NORMAL
                        )
                        rxBus.send(EventNewNotification(notification))
                    }
                    validityCheck.isValid = false
                    validityCheck.reasons.add(
                        resourceHelper.gs(
                            R.string.basalprofilenotaligned,
                            from
                        )
                    )
                    break
                }
            }
            // Check for minimal basal value
            if (basalAmount < description.basalMinimumRate) {
                basal.amount = description.basalMinimumRate
                if (notify) sendBelowMinimumNotification(from, rxBus, resourceHelper)
                validityCheck.isValid = false
                validityCheck.reasons.add(resourceHelper.gs(R.string.minimalbasalvaluereplaced, from))
                break
            } else if (basalAmount > description.basalMaximumRate) {
                basal.amount = description.basalMaximumRate
                if (notify) sendAboveMaximumNotification(from, rxBus, resourceHelper)
                validityCheck.isValid = false
                validityCheck.reasons.add(resourceHelper.gs(R.string.maximumbasalvaluereplaced, from))
                break
            }
            if (!hardLimits.isInRange(basalAmount, 0.01, hardLimits.maxBasal())) {
                validityCheck.isValid = false
                validityCheck.reasons.add(resourceHelper.gs(R.string.value_out_of_hard_limits, resourceHelper.gs(R.string.basal_value), basalAmount))
                break
            }
        }
        if (!hardLimits.isInRange(dia, hardLimits.minDia(), hardLimits.maxDia())) {
            validityCheck.isValid = false
            validityCheck.reasons.add(resourceHelper.gs(R.string.value_out_of_hard_limits, resourceHelper.gs(R.string.profile_dia), dia))
        }
        for (ic in icBlocks)
            if (!hardLimits.isInRange(ic.amount * 100.0 / percentage, hardLimits.minIC(), hardLimits.maxIC())) {
                validityCheck.isValid = false
                validityCheck.reasons.add(resourceHelper.gs(R.string.value_out_of_hard_limits, resourceHelper.gs(R.string.profile_carbs_ratio_value), ic.amount * 100.0 / percentage))
                break
            }
        for (isf in isfBlocks)
            if (!hardLimits.isInRange(toMgdl(isf.amount * 100.0 / percentage, units), HardLimits.MIN_ISF, HardLimits.MAX_ISF)) {
                validityCheck.isValid = false
                validityCheck.reasons.add(resourceHelper.gs(R.string.value_out_of_hard_limits, resourceHelper.gs(R.string.profile_sensitivity_value), isf.amount * 100.0 / percentage))
                break
            }
        for (target in targetBlocks) {
            if (!hardLimits.isInRange(
                    Round.roundTo(target.lowTarget, 0.1),
                    HardLimits.VERY_HARD_LIMIT_MIN_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_MIN_BG[1].toDouble()
                )
            ) {
                validityCheck.isValid = false
                validityCheck.reasons.add(resourceHelper.gs(R.string.value_out_of_hard_limits, resourceHelper.gs(R.string.profile_low_target), target.lowTarget))
                break
            }
            if (!hardLimits.isInRange(
                    Round.roundTo(target.highTarget, 0.1),
                    HardLimits.VERY_HARD_LIMIT_MAX_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_MAX_BG[1].toDouble()
                )
            ) {
                validityCheck.isValid = false
                validityCheck.reasons.add(resourceHelper.gs(R.string.value_out_of_hard_limits, resourceHelper.gs(R.string.profile_high_target), target.highTarget))
                break
            }
        }
        return validityCheck
    }

    protected open fun sendBelowMinimumNotification(from: String, rxBus: RxBusWrapper, resourceHelper: ResourceHelper) {
        rxBus.send(EventNewNotification(Notification(Notification.MINIMAL_BASAL_VALUE_REPLACED, resourceHelper.gs(R.string.minimalbasalvaluereplaced, from), Notification.NORMAL)))
    }

    protected open fun sendAboveMaximumNotification(from: String, rxBus: RxBusWrapper, resourceHelper: ResourceHelper) {
        rxBus.send(EventNewNotification(Notification(Notification.MAXIMUM_BASAL_VALUE_REPLACED, resourceHelper.gs(R.string.maximumbasalvaluereplaced, from), Notification.NORMAL)))
    }

    override val units: GlucoseUnit
        get() = when (this) {
            is PS   -> if (value.glucoseUnit == ProfileSwitch.GlucoseUnit.MMOL) GlucoseUnit.MMOL else GlucoseUnit.MGDL
            is EPS  -> if (value.glucoseUnit == EffectiveProfileSwitch.GlucoseUnit.MMOL) GlucoseUnit.MMOL else GlucoseUnit.MGDL
            is Pure -> value.glucoseUnit
        }
    override val dia: Double
        get() = insulinConfiguration.insulinEndTime / 1000.0 / 60.0 / 60.0

    override val timeshift: Int
        get() = ts

    override fun isEqual(profile: Profile): Boolean {
        for (hour in 0..23) {
            val seconds = T.hours(hour.toLong()).secs().toInt()
            if (getBasalTimeFromMidnight(seconds) !=  profile.getBasalTimeFromMidnight(seconds)) return false
            if (getIsfMgdlTimeFromMidnight(seconds) !=  profile.getIsfMgdlTimeFromMidnight(seconds)) return false
            if (getIcTimeFromMidnight(seconds) !=  profile.getIcTimeFromMidnight(seconds)) return false
            if (getTargetLowMgdlTimeFromMidnight(seconds) !=  profile.getTargetLowMgdlTimeFromMidnight(seconds)) return false
            if (getTargetHighMgdlTimeFromMidnight(seconds) !=  profile.getTargetHighMgdlTimeFromMidnight(seconds)) return false
            if (dia != profile.dia) return false
        }
        return true
    }

    override val percentage: Int
        get() = pct

    override fun getBasal(): Double = basalBlocks.blockValueBySeconds(secondsFromMidnight(), percentage / 100.0, timeshift)
    override fun getBasal(timestamp: Long): Double = basalBlocks.blockValueBySeconds(secondsFromMidnight(timestamp), percentage / 100.0, timeshift)
    override fun getIc(): Double = icBlocks.blockValueBySeconds(secondsFromMidnight(), 100.0 / percentage, timeshift)
    override fun getIc(timestamp: Long): Double = icBlocks.blockValueBySeconds(secondsFromMidnight(timestamp), 100.0 / percentage, timeshift)
    override fun getIsfMgdl(): Double = toMgdl(isfBlocks.blockValueBySeconds(secondsFromMidnight(), 100.0 / percentage, timeshift), units)
    override fun getIsfMgdl(timestamp: Long): Double = toMgdl(isfBlocks.blockValueBySeconds(secondsFromMidnight(timestamp), 100.0 / percentage, timeshift), units)
    override fun getTargetMgdl(): Double = toMgdl(targetBlocks.targetBlockValueBySeconds(secondsFromMidnight(), timeshift), units)
    override fun getTargetLowMgdl(): Double = toMgdl(targetBlocks.lowTargetBlockValueBySeconds(secondsFromMidnight(), timeshift), units)
    override fun getTargetLowMgdl(timestamp: Long): Double = toMgdl(targetBlocks.lowTargetBlockValueBySeconds(secondsFromMidnight(timestamp), timeshift), units)
    override fun getTargetHighMgdl(): Double = toMgdl(targetBlocks.highTargetBlockValueBySeconds(secondsFromMidnight(), timeshift), units)
    override fun getTargetHighMgdl(timestamp: Long): Double = toMgdl(targetBlocks.highTargetBlockValueBySeconds(secondsFromMidnight(timestamp), timeshift), units)
    override fun getBasalTimeFromMidnight(timeAsSeconds: Int): Double = basalBlocks.blockValueBySeconds(timeAsSeconds, percentage / 100.0, timeshift)
    override fun getIcTimeFromMidnight(timeAsSeconds: Int): Double = icBlocks.blockValueBySeconds(timeAsSeconds, 100.0 / percentage, timeshift)
    fun getIsfTimeFromMidnight(timeAsSeconds: Int): Double = isfBlocks.blockValueBySeconds(timeAsSeconds, 100.0 / percentage, timeshift)
    override fun getIsfMgdlTimeFromMidnight(timeAsSeconds: Int): Double = toMgdl(isfBlocks.blockValueBySeconds(timeAsSeconds, 100.0 / percentage, timeshift), units)
    override fun getTargetLowMgdlTimeFromMidnight(timeAsSeconds: Int): Double = toMgdl(targetBlocks.lowTargetBlockValueBySeconds(timeAsSeconds, timeshift), units)
    private fun getTargetLowTimeFromMidnight(timeAsSeconds: Int): Double = targetBlocks.lowTargetBlockValueBySeconds(timeAsSeconds, timeshift)
    private fun getTargetHighTimeFromMidnight(timeAsSeconds: Int): Double = targetBlocks.highTargetBlockValueBySeconds(timeAsSeconds, timeshift)
    override fun getTargetHighMgdlTimeFromMidnight(timeAsSeconds: Int): Double = toMgdl(targetBlocks.highTargetBlockValueBySeconds(timeAsSeconds, timeshift), units)

    override fun getIcList(resourceHelper: ResourceHelper, dateUtil: DateUtil): String = getValuesList(icBlocks, 100.0 / percentage, DecimalFormat("0.0"), resourceHelper.gs(R.string.profile_carbs_per_unit), dateUtil)
    override fun getIsfList(resourceHelper: ResourceHelper, dateUtil: DateUtil): String = getValuesList(isfBlocks, 100.0 / percentage, DecimalFormat("0.0"), units.asText + resourceHelper.gs(R.string.profile_per_unit), dateUtil)
    override fun getBasalList(resourceHelper: ResourceHelper, dateUtil: DateUtil): String = getValuesList(basalBlocks, percentage / 100.0, DecimalFormat("0.00"), resourceHelper.gs(R.string.profile_ins_units_per_hour), dateUtil)
    override fun getTargetList(resourceHelper: ResourceHelper, dateUtil: DateUtil): String = getTargetValuesList(targetBlocks, DecimalFormat("0.0"), units.asText, dateUtil)

    override fun convertToNonCustomizedProfile(dateUtil: DateUtil): PureProfile =
        PureProfile(
            jsonObject = toPureNsJson(dateUtil),
            basalBlocks = basalBlocks.shiftBlock(percentage / 100.0, timeshift),
            isfBlocks = isfBlocks.shiftBlock(100.0 / percentage, timeshift),
            icBlocks = icBlocks.shiftBlock(100.0 / percentage, timeshift),
            targetBlocks = targetBlocks.shiftTargetBlock(timeshift),
            glucoseUnit = units,
            dia = when (this) {
                is PS   -> this.value.insulinConfiguration.insulinEndTime / 3600.0 / 1000.0
                is EPS  -> this.value.insulinConfiguration.insulinEndTime / 3600.0 / 1000.0
                is Pure -> this.value.dia
            },
            timeZone = TimeZone.getDefault()
        )

    override fun toPureNsJson(dateUtil: DateUtil): JSONObject {
        val o = JSONObject()
        o.put("units", units.asText)
        o.put("dia", dia)
        o.put("timezone", dateUtil.timeZoneByOffset(utcOffset).id ?: "UTC")
        // SENS
        val sens = JSONArray()
        var elapsedHours = 0L
        isfBlocks.forEach {
            sens.put(JSONObject()
                .put("time", DecimalFormat("00").format(elapsedHours) + ":00")
                .put("timeAsSeconds", T.hours(elapsedHours).secs())
                .put("value", getIsfTimeFromMidnight(T.hours(elapsedHours).secs().toInt()))
            )
            elapsedHours += T.msecs(it.duration).hours()
        }
        o.put("sens", sens)
        val carbratio = JSONArray()
        elapsedHours = 0L
        icBlocks.forEach {
            carbratio.put(JSONObject()
                .put("time", DecimalFormat("00").format(elapsedHours) + ":00")
                .put("timeAsSeconds", T.hours(elapsedHours).secs())
                .put("value", getIcTimeFromMidnight(T.hours(elapsedHours).secs().toInt()))
            )
            elapsedHours += T.msecs(it.duration).hours()
        }
        o.put("carbratio", carbratio)
        val basal = JSONArray()
        elapsedHours = 0L
        basalBlocks.forEach {
            basal.put(JSONObject()
                .put("time", DecimalFormat("00").format(elapsedHours) + ":00")
                .put("timeAsSeconds", T.hours(elapsedHours).secs())
                .put("value", getBasalTimeFromMidnight(T.hours(elapsedHours).secs().toInt()))
            )
            elapsedHours += T.msecs(it.duration).hours()
        }
        o.put("basal", basal)
        val targetLow = JSONArray()
        val targetHigh = JSONArray()
        elapsedHours = 0L
        targetBlocks.forEach {
            targetLow.put(JSONObject()
                .put("time", DecimalFormat("00").format(elapsedHours) + ":00")
                .put("timeAsSeconds", T.hours(elapsedHours).secs())
                .put("value", getTargetLowTimeFromMidnight(T.hours(elapsedHours).secs().toInt()))
            )
            targetHigh.put(JSONObject()
                .put("time", DecimalFormat("00").format(elapsedHours) + ":00")
                .put("timeAsSeconds", T.hours(elapsedHours).secs())
                .put("value", getTargetHighTimeFromMidnight(T.hours(elapsedHours).secs().toInt()))
            )
            elapsedHours += T.msecs(it.duration).hours()
        }
        o.put("target_low", targetLow)
        o.put("target_high", targetHigh)
        return o
    }

    override fun getMaxDailyBasal(): Double = basalBlocks.maxByOrNull { it.amount }?.amount ?: 0.0

    override fun baseBasalSum(): Double {
        var result = 0.0
        for (i in 0..23) result += getBasalTimeFromMidnight(i * 60 * 60) / (percentage / 100.0) // it's recalculated. we need to recalculate back
        return result
    }

    override fun percentageBasalSum(): Double {
        var result = 0.0
        for (i in 0..23) result += getBasalTimeFromMidnight(i * 60 * 60)
        return result
    }

    override fun getBasalValues(): Array<ProfileValue> = getValues(basalBlocks, percentage / 100.0)
    override fun getIcsValues(): Array<ProfileValue> = getValues(icBlocks, 100.0 / percentage)

    override fun getIsfsMgdlValues(): Array<ProfileValue> {
        val shifted = isfBlocks.shiftBlock(100.0 / percentage, timeshift)
        val ret = Array(shifted.size) { ProfileValue(0, 0.0) }
        var elapsed = 0
        for (index in shifted.indices) {
            ret[index] = ProfileValue(elapsed, toMgdl(shifted[index].amount, units))
            elapsed += T.msecs(shifted[index].duration).secs().toInt()
        }
        return ret
    }

    private fun getValues(block: List<Block>, multiplier: Double): Array<ProfileValue> {
        val shifted = block.shiftBlock(multiplier, timeshift)
        val ret = Array(shifted.size) { ProfileValue(0, 0.0) }
        var elapsed = 0
        for (index in shifted.indices) {
            ret[index] = ProfileValue(elapsed, shifted[index].amount)
            elapsed += T.msecs(shifted[index].duration).secs().toInt()
        }
        return ret
    }

    override fun getSingleTargetsMgdl(): Array<ProfileValue> {
        val shifted = targetBlocks.shiftTargetBlock(timeshift)
        val ret = Array(shifted.size) { ProfileValue(0, 0.0) }
        var elapsed = 0
        for (index in shifted.indices) {
            ret[index] = ProfileValue(elapsed, (shifted[index].lowTarget + shifted[index].highTarget) / 2.0)
            elapsed += T.msecs(shifted[index].duration).secs().toInt()
        }
        return ret
    }

    private fun getValuesList(array: List<Block>, multiplier: Double, format: DecimalFormat, units: String, dateUtil: DateUtil): String =
        StringBuilder().also { sb ->
            var elapsedSec = 0
            array.shiftBlock(multiplier, timeshift).forEach {
                if (elapsedSec != 0) sb.append("\n")
                sb.append(dateUtil.format_HH_MM(elapsedSec))
                    .append("    ")
                    .append(format.format(it.amount * multiplier))
                    .append(" $units")
                elapsedSec += T.msecs(it.duration).secs().toInt()
            }
        }.toString()

    private fun getTargetValuesList(array: List<TargetBlock>, format: DecimalFormat, units: String, dateUtil: DateUtil): String =
        StringBuilder().also { sb ->
            var elapsedSec = 0
            array.shiftTargetBlock(timeshift).forEach {
                if (elapsedSec != 0) sb.append("\n")
                sb.append(dateUtil.format_HH_MM(elapsedSec))
                    .append("    ")
                    .append(format.format(it.lowTarget))
                    .append(" - ")
                    .append(format.format(it.highTarget))
                    .append(" $units")
                elapsedSec += T.msecs(it.duration).secs().toInt()
            }
        }.toString()

    fun isInProgress(dateUtil: DateUtil): Boolean =
        dateUtil.now() in timestamp..timestamp + (duration ?: 0L)

}
