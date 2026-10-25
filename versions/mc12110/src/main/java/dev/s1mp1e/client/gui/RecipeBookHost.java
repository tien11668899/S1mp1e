package dev.s1mp1e.client.gui;

/** Implemented by {@code RecipeBookComponent} (RecipeBookGlassMixin): exposes the book's private geometry for the slide. */
public interface RecipeBookHost {
   int liquidglass$xOrigin();

   /** Centre of the book's whole visual bounds (body + left tab column) — the anchor for the open/close scale-in. */
   int liquidglass$bookCenterX();

   int liquidglass$bookCenterY();

   /** The book body's right edge (the side nearest the inventory) — the anchor for the right-to-left unfold. */
   int liquidglass$bookRight();
}
