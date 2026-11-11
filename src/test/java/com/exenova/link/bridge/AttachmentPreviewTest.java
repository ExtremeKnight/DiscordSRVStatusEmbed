package com.exenova.link.bridge;

import org.junit.jupiter.api.Test;

import static com.exenova.link.bridge.AttachmentPreview.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentPreviewTest {
    @Test
    void classifiesByExtension() {
        assertEquals(GIF, AttachmentPreview.kind("meme.gif", false));
        assertEquals(IMAGE, AttachmentPreview.kind("cat.PNG", false));
        assertEquals(IMAGE, AttachmentPreview.kind("a.jpeg", false));
        assertEquals(VIDEO, AttachmentPreview.kind("clip.mp4", false));
        assertEquals(AUDIO, AttachmentPreview.kind("song.mp3", false));
        assertEquals(FILE, AttachmentPreview.kind("notes.txt", false));
    }

    @Test
    void isImageFlagForcesImage() {
        assertEquals(IMAGE, AttachmentPreview.kind("weirdname", true));
        // but a .gif stays a gif even if flagged as image
        assertEquals(GIF, AttachmentPreview.kind("x.gif", true));
    }

    @Test
    void labels() {
        assertEquals("[gif]", AttachmentPreview.label("x.gif", false));
        assertEquals("[image: cat.png]", AttachmentPreview.label("cat.png", false));
        assertEquals("[video: clip.mp4]", AttachmentPreview.label("clip.mp4", false));
        assertEquals("[file]", AttachmentPreview.label("", false));
    }
}
