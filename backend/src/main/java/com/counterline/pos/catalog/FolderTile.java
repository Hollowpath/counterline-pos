package com.counterline.pos.catalog;

/** Query projection: a category folder placed on a tab grid. */
record FolderTile(Long categoryId, String name, Long tabId, Short gridRow, Short gridCol, long itemCount) {
}
