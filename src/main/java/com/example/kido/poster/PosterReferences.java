package com.example.kido.poster;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every component a layout points at, and what kind each one has to be.
 *
 * <p>One place on purpose: saving a template checks these, and deleting a component
 * checks the same set from the other direction. Two readings of "which ids does this
 * layout use" would eventually disagree, and the disagreement would show up as a
 * deleted sticker that a template still expects.
 */
final class PosterReferences {
    private PosterReferences() {}

    static Map<String, PosterComponentType> of(PosterLayout layout) {
        Map<String, PosterComponentType> refs = new LinkedHashMap<>();
        if (layout == null) {
            return refs;
        }
        put(refs, layout.fontComponentId(), PosterComponentType.FONT);
        if (layout.textBoxes() != null) {
            layout.textBoxes().forEach(box -> put(refs, box.fontComponentId(), PosterComponentType.FONT));
        }
        if (layout.imageSlots() != null) {
            layout.imageSlots().forEach(slot -> put(refs, slot.frameComponentId(), PosterComponentType.FRAME));
        }
        if (layout.stickers() != null) {
            layout.stickers().forEach(sticker -> put(refs, sticker.componentId(), PosterComponentType.STICKER));
        }
        return refs;
    }

    private static void put(Map<String, PosterComponentType> refs, String id, PosterComponentType type) {
        if (id != null && !id.isBlank()) {
            refs.put(id.trim(), type);
        }
    }
}
