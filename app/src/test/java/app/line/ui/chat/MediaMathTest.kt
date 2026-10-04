package app.line.ui.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMathTest {
    @Test fun landscapePhotoFillsTheWidth() {
        val box = ImageSizing.box(2000, 1000, 260, 340, 120)
        assertEquals(ImageSizing.Box(260, 130), box)
    }

    @Test fun portraitPhotoIsLimitedByHeight() {
        val box = ImageSizing.box(1000, 2000, 260, 340, 120)
        assertEquals(ImageSizing.Box(170, 340), box)
    }

    @Test fun extremeRatiosAreClampedAndNeverBecomeSlivers() {
        val panorama = ImageSizing.box(8000, 400, 260, 340, 120)
        assertEquals(260, panorama.width)
        assertTrue(panorama.height >= 120)
        val tower = ImageSizing.box(100, 3000, 260, 340, 120)
        assertTrue(tower.width >= 120)
        assertEquals(340, tower.height)
    }

    @Test fun unknownSizeGetsAPlaceholderBox() {
        assertEquals(ImageSizing.Box(260, 195), ImageSizing.box(null, null, 260, 340, 120))
        assertEquals(ImageSizing.Box(260, 195), ImageSizing.box(0, 10, 260, 340, 120))
    }

    @Test fun durationsFormatLikeAPlayer() {
        assertEquals("0:00", VoiceMath.duration(0))
        assertEquals("0:07", VoiceMath.duration(7_400))
        assertEquals("0:08", VoiceMath.duration(7_400, roundUp = true))
        assertEquals("12:30", VoiceMath.duration(750_000))
        assertEquals("1:02:03", VoiceMath.duration(3_723_000))
        assertEquals("0:00", VoiceMath.duration(-5))
    }

    @Test fun waveformKeepsPeaksWhenShrinkingAndRepeatsWhenGrowing() {
        val source = byteArrayOf(10, 90, 20, 30, 40, 50, 60, 70)
        assertArrayEquals(intArrayOf(90, 30, 50, 70), VoiceMath.bars(source, 4))
        assertArrayEquals(intArrayOf(10, 10, 90, 90), VoiceMath.bars(byteArrayOf(10, 90), 4))
    }

    @Test fun missingWaveformIsFlatAtTheFloor() {
        assertArrayEquals(intArrayOf(6, 6, 6), VoiceMath.bars(null, 3))
        assertArrayEquals(intArrayOf(6, 6, 6), VoiceMath.bars(ByteArray(0), 3))
    }

    @Test fun speedCyclesThroughThePresetsOnly() {
        assertEquals(1.5f, VoiceMath.nextSpeed(1f), 0f)
        assertEquals(2f, VoiceMath.nextSpeed(1.5f), 0f)
        assertEquals(1f, VoiceMath.nextSpeed(2f), 0f)
        assertEquals(1f, VoiceMath.nextSpeed(0.5f), 0f)
    }

    @Test fun seekFractionIsClampedToTheWaveform() {
        assertEquals(0f, VoiceMath.fraction(-30f, 100f, 200f), 0f)
        assertEquals(0.5f, VoiceMath.fraction(200f, 100f, 200f), 0f)
        assertEquals(1f, VoiceMath.fraction(900f, 100f, 200f), 0f)
        assertEquals(0f, VoiceMath.fraction(10f, 0f, 0f), 0f)
        assertEquals(24, VoiceMath.playedBars(0.5f, 48))
    }

    @Test fun sizesUseBinaryUnitsWithOneDecimalBelowTen() {
        assertEquals(SizeFormat.Size(SizeFormat.Unit.B, 512.0, 0), SizeFormat.of(512))
        val kb = SizeFormat.of(2048)
        assertEquals(SizeFormat.Unit.KB, kb.unit); assertEquals(1, kb.fractionDigits)
        val mb = SizeFormat.of(25L * 1024 * 1024)
        assertEquals(SizeFormat.Unit.MB, mb.unit); assertEquals(0, mb.fractionDigits)
        assertEquals(SizeFormat.Unit.B, SizeFormat.of(-1).unit)
    }

    @Test fun fileIconFollowsTheMimeType() {
        assertEquals("image", FileKinds.icon("image/png"))
        assertEquals("waveform", FileKinds.icon("audio/mp4"))
        assertEquals("file", FileKinds.icon("application/pdf"))
    }
}
