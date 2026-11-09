package projectcommon.error

/** A local SQLite store failing because of the device, not the code. Out of space is not one: that is
 *  the [DISK_FULL_CODE] category, which [codedError] already finds. */
enum class StorageFault {
    /** The file is damaged or not a database. Android deletes it when it reports this, so the store
     *  opens empty on the next launch. */
    Corrupt,

    /** The file could not be opened or read right now: an I/O error, or no memory for a cursor window. */
    Unavailable,
}

/** The [StorageFault] in the cause chain, matched on the Android framework's typed SQLite exceptions
 *  by class name, which keeps this module free of platform code. */
fun Throwable.storageFault(): StorageFault? {
    var t: Throwable? = this
    var depth = 0 // cause chains can be cyclic on some platforms
    while (t != null && depth++ < 100) {
        when (t::class.simpleName) {
            "SQLiteDatabaseCorruptException" -> return StorageFault.Corrupt
            "SQLiteCantOpenDatabaseException", "SQLiteDiskIOException", "CursorWindowAllocationException" ->
                return StorageFault.Unavailable
        }
        t = t.cause
    }
    return null
}

/** The device, not the code, ended the work: no network, full storage, or a broken local store. The
 *  user retries, frees space, or the store starts over, so it is never a crash record. */
fun Throwable.isEnvironmental(): Boolean {
    val code = errorCode()
    return code == NETWORK_CODE || code == DISK_FULL_CODE || storageFault() != null
}
