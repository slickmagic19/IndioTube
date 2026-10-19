package com.indiotube.app.player

import android.text.Layout
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

// YouTube's auto-generated captions arrive left-aligned in the bottom-left
// corner (`align:start position:0%` in the WebVTT), while manual captions are
// normally centered. IndioTube always shows captions centered, like YouTube
// itself, so every parsed cue is normalized here: horizontal position,
// anchors and the explicit line are cleared and the text is centered. Clearing
// the line also lets SubtitleView place captions using the bottom padding
// fraction instead of the position baked into the caption file.
class CenteringSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser =
        CenteringSubtitleParser(delegate.create(format))
}

private class CenteringSubtitleParser(
    private val delegate: SubtitleParser,
) : SubtitleParser {

    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior

    override fun parse(
        input: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        delegate.parse(input, offset, length, outputOptions) { cues ->
            output.accept(cues.centered())
        }
    }

    override fun reset() = delegate.reset()
}

private fun CuesWithTiming.centered(): CuesWithTiming {
    if (cues.isEmpty()) return this
    val normalized = cues.map { cue ->
        cue.buildUpon()
            .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLine(Cue.DIMEN_UNSET, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.TYPE_UNSET)
            .setPosition(Cue.DIMEN_UNSET)
            .setPositionAnchor(Cue.TYPE_UNSET)
            .build()
    }
    return CuesWithTiming(normalized, startTimeUs, durationUs)
}
