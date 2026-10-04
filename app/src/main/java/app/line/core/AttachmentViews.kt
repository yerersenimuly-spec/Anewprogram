package app.line.core

/** Thread-safe, bounded memory cache behind `CallService.attachment`; the pipeline keeps the entries current. */
class AttachmentViews(private val capacity: Int = 600) {
    private val entries = object : LinkedHashMap<String, AttachmentView>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AttachmentView>): Boolean = size > capacity
    }

    private var generation = 0L

    @Synchronized fun get(messageId: String): AttachmentView? = entries[messageId]

    /** Counts every change; lets a loader detect that a transfer moved on while it was reading older data. */
    @Synchronized fun generation(): Long = generation

    @Synchronized fun put(view: AttachmentView) {
        generation++
        entries[view.messageId] = view
    }

    @Synchronized fun putIfAbsent(view: AttachmentView) {
        if (!entries.containsKey(view.messageId)) put(view)
    }

    /** Adds [views] only when nothing changed since [expected] was read; false (and no change) otherwise. */
    @Synchronized fun putAllIfUnchanged(views: List<AttachmentView>, expected: Long): Boolean {
        if (generation != expected) return false
        views.forEach { entries[it.messageId] = it }
        generation++
        return true
    }

    /** Applies [change] to a cached view; returns the new view, or null when the message is not cached. */
    @Synchronized fun update(messageId: String, change: (AttachmentView) -> AttachmentView): AttachmentView? {
        generation++
        val current = entries[messageId] ?: return null
        return change(current).also { entries[messageId] = it }
    }

    @Synchronized fun remove(messageId: String) {
        generation++
        entries.remove(messageId)
    }

    @Synchronized fun clear() {
        generation++
        entries.clear()
    }

    companion object {
        /**
         * [stage] is null for a message without a row (should not happen): an outgoing message then reads as failed
         * when its status says so and as queued otherwise.
         */
        fun build(
            messageId: String,
            payload: AttachmentPayload,
            outgoing: Boolean,
            stage: StoredStage?,
            played: Boolean,
            status: MessageStatus?,
            transferring: Boolean = false,
            progress: Float = 0f,
        ): AttachmentView {
            val transfer = stage?.toTransferStage(transferring)
                ?: if (outgoing && status == MessageStatus.FAILED) TransferStage.FAILED else TransferStage.QUEUED
            val shown = if (transfer == TransferStage.TRANSFERRING) progress.coerceIn(0f, 1f) else if (transfer == TransferStage.READY) 1f else 0f
            return AttachmentView(messageId, payload, transfer, shown, outgoing, played)
        }
    }
}
