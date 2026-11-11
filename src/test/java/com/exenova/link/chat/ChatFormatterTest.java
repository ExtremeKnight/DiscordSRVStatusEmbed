package com.exenova.link.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ChatFormatterTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static boolean hasClick(Component component) {
        AtomicBoolean found = new AtomicBoolean(false);
        walk(component, found);
        return found.get();
    }

    private static void walk(Component component, AtomicBoolean found) {
        if (component.clickEvent() != null) found.set(true);
        component.children().forEach(child -> walk(child, found));
    }

    @Test
    void appliesTemplateAndInsertsNameAndMessage() {
        Component out = ChatFormatter.render("<gray>{player}</gray> » {message}", "Steve", "hello", false);
        assertEquals("Steve » hello", PLAIN.serialize(out));
    }

    @Test
    void playerMessageTagsAreNotParsedWhenColorDisallowed() {
        // The player typed MiniMessage tags; they must appear literally, not be interpreted.
        Component out = ChatFormatter.render("{player}: {message}", "Steve", "<red>hi</red>", false);
        assertEquals("Steve: <red>hi</red>", PLAIN.serialize(out));
        assertFalse(hasClick(out));
    }

    @Test
    void clickAndHoverNeverComeFromPlayerEvenWithColorAllowed() {
        // Even with colour allowed, a malicious click tag must not create a click event.
        Component out = ChatFormatter.render("{player}: {message}", "Steve",
                "<click:run_command:/op Steve><red>hi</red></click>", true);
        assertFalse(hasClick(out), "player input must never produce a click event");
        assertTrue(PLAIN.serialize(out).contains("hi"));
    }

    @Test
    void colorTagsInPlayerMessageApplyWhenAllowed() {
        Component out = ChatFormatter.render("{message}", "Steve", "<red>hi</red>", true);
        assertEquals("hi", PLAIN.serialize(out));
        // The rendered text node should carry red somewhere in the tree.
        AtomicBoolean red = new AtomicBoolean(false);
        checkRed(out, red);
        assertTrue(red.get(), "colour tag should be applied when allowed");
    }

    private static void checkRed(Component component, AtomicBoolean red) {
        if (NamedTextColor.RED.equals(component.color())) red.set(true);
        component.children().forEach(child -> checkRed(child, red));
    }

    @Test
    void templateMiniMessageStillStyles() {
        Component out = ChatFormatter.render("<red>{player}</red>: {message}", "Steve", "hi", false);
        AtomicBoolean red = new AtomicBoolean(false);
        checkRed(out, red);
        assertTrue(red.get(), "template colour should apply to the name");
    }
}
