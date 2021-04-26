package info.nightscout.androidaps.database.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import info.nightscout.androidaps.database.TABLE_TEMPORARY_BASALS
import info.nightscout.androidaps.database.embedments.InterfaceIDs
import info.nightscout.androidaps.database.interfaces.DBEntryWithTimeAndDuration
import info.nightscout.androidaps.database.interfaces.TraceableDBEntry
import java.util.*

@Entity(tableName = TABLE_TEMPORARY_BASALS,
    foreignKeys = [ForeignKey(
        entity = TemporaryBasal::class,
        parentColumns = ["id"],
        childColumns = ["referenceId"])],
    indices = [Index("referenceId"), Index("timestamp")])
data class TemporaryBasal(
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    @Embedded
    override var interfaceIDs_backing: InterfaceIDs? = InterfaceIDs(),
    override var timestamp: Long,
    override var utcOffset: Long = TimeZone.getDefault().getOffset(timestamp).toLong(),
    var type: Type,
    var isAbsolute: Boolean,
    var rate: Double,
    override var duration: Long
) : TraceableDBEntry, DBEntryWithTimeAndDuration {

    init {
        if (duration <= 0)
            require(duration > 0)
    }

    enum class Type {
        NORMAL,
        EMULATED_PUMP_SUSPEND,
        PUMP_SUSPEND,
        SUPERBOLUS,
        FAKE_EXTENDED // in memory only
        ;

        companion object {

            fun fromString(name: String?) = values().firstOrNull { it.name == name } ?: NORMAL
        }
    }

    val isInProgress: Boolean
        get() = System.currentTimeMillis() in timestamp..timestamp + duration
}