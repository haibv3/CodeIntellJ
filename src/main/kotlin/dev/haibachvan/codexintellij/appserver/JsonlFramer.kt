package dev.haibachvan.codexintellij.appserver

/**
 * Per-epoch JSONL framer with drain-first backpressure support.
 * Oversized records emit one diagnostic and discard through newline; EOF never emits partial JSON.
 * Framing is byte-oriented so UTF-8 codepoints may safely split across chunks.
 */
class JsonlFramer(
    private val maxRecordBytes: Int = DEFAULT_MAX_RECORD_BYTES,
) {
    enum class State {
        Normal,
        DiscardUntilNewline,
    }

    data class Frame(
        val epoch: ProcessEpoch,
        val line: String,
        val byteLength: Int,
    )

    data class FramerDiagnostic(
        val epoch: ProcessEpoch,
        val code: String,
        val message: String,
    )

    data class AcceptResult(
        val frames: List<Frame>,
        val diagnostics: List<FramerDiagnostic>,
    )

    private var epoch: ProcessEpoch? = null
    private var state: State = State.Normal
    private val buffer = ArrayList<Byte>(1024)
    private var oversizeDiagnosticEmitted = false

    fun state(): State = state

    fun accept(epoch: ProcessEpoch, bytes: ByteArray): AcceptResult {
        resetIfNeeded(epoch)
        val frames = ArrayList<Frame>()
        val diagnostics = ArrayList<FramerDiagnostic>()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            when (state) {
                State.DiscardUntilNewline -> {
                    if (b == '\n'.code.toByte()) {
                        state = State.Normal
                        buffer.clear()
                        oversizeDiagnosticEmitted = false
                    }
                }
                State.Normal -> {
                    if (b == '\n'.code.toByte()) {
                        val raw = buffer.toByteArray()
                        buffer.clear()
                        val lineBytes = trimTrailingCr(raw)
                        if (lineBytes.isNotEmpty()) {
                            frames += Frame(
                                epoch = epoch,
                                line = lineBytes.toString(Charsets.UTF_8),
                                byteLength = lineBytes.size,
                            )
                        }
                    } else {
                        buffer.add(b)
                        if (buffer.size > maxRecordBytes) {
                            state = State.DiscardUntilNewline
                            if (!oversizeDiagnosticEmitted) {
                                oversizeDiagnosticEmitted = true
                                diagnostics += FramerDiagnostic(
                                    epoch = epoch,
                                    code = "oversize_record",
                                    message = "Discarding oversized JSONL record (> $maxRecordBytes bytes)",
                                )
                            }
                            buffer.clear()
                        }
                    }
                }
            }
            i += 1
        }
        return AcceptResult(frames = frames, diagnostics = diagnostics)
    }

    fun finish(epoch: ProcessEpoch): AcceptResult {
        resetIfNeeded(epoch)
        val diagnostics = ArrayList<FramerDiagnostic>()
        if (buffer.isNotEmpty() || state == State.DiscardUntilNewline) {
            diagnostics += FramerDiagnostic(
                epoch = epoch,
                code = "truncated_frame",
                message = "EOF with incomplete JSONL frame; partial bytes discarded",
            )
        }
        buffer.clear()
        state = State.Normal
        oversizeDiagnosticEmitted = false
        return AcceptResult(frames = emptyList(), diagnostics = diagnostics)
    }

    fun reset(epoch: ProcessEpoch) {
        this.epoch = epoch
        state = State.Normal
        buffer.clear()
        oversizeDiagnosticEmitted = false
    }

    private fun resetIfNeeded(epoch: ProcessEpoch) {
        if (this.epoch != epoch) {
            reset(epoch)
        }
    }

    private fun trimTrailingCr(raw: ByteArray): ByteArray {
        if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) {
            return raw.copyOf(raw.size - 1)
        }
        return raw
    }

    companion object {
        const val DEFAULT_MAX_RECORD_BYTES: Int = 8 * 1024 * 1024
    }
}
