package dev.s1mp1e.client.gui;

/**
 * Implemented by every {@code AbstractSelectionList} through {@code SelectionListGlideMixin}: the selected row's glass
 * highlight is drawn once per frame under every row at its gliding position, so the row's own selection box is skipped
 * ({@code SelectionGlassMixin} asks here before drawing it).
 */
public interface SelectionGlideHost {
   /** True when {@code entry}'s highlight was already drawn this frame at the gliding position. */
   boolean liquidglass$glidesOver(Object entry);
}
