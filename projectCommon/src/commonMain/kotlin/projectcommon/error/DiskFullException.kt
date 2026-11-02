package projectcommon.error

/** Device storage is full — a well-known category ([DISK_FULL_CODE]): the user frees space, so it renders
 *  as its own message and is never a crash record. */
class DiskFullException(cause: Throwable? = null) :
    Exception("storage is full", cause),
    CodedError {
    override val code: String = DISK_FULL_CODE
    override val userMessage: String? = null
}

const val DISK_FULL_CODE = "disk_full"

/** An uncoded platform out-of-space failure: SQLite's SQLITE_FULL, SQLite failing to grow its WAL
 *  index (SQLITE_IOERR_SHMSIZE, seen only with zero free storage), or an ENOSPC write. */
internal fun Throwable.isOutOfSpace(): Boolean {
    if (this::class.simpleName == "SQLiteFullException") return true
    val text = message ?: return false
    return "ENOSPC" in text || "No space left on device" in text || "SQLITE_FULL" in text || "SQLITE_IOERR_SHMSIZE" in text
}
