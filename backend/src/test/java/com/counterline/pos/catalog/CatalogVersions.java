package com.counterline.pos.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catalogue edits carry the version of the row they were made from. A test that just wants to make an edit has its
 * body stamped with the row's current version here (as the dashboard form would carry the version it loaded); a body
 * that already names a version is left alone, which is how the stale-edit tests send an old one.
 */
public final class CatalogVersions {

    private static final Pattern EDIT = Pattern.compile("^.*/(folders|items|tabs|modifier-groups|modifier-options)/(\\d+)$");
    private static final Map<String, String> TABLES = Map.of(
            "folders", "category", "items", "item", "tabs", "quickgrid_tab",
            "modifier-groups", "modifier_group", "modifier-options", "modifier_option");

    private CatalogVersions() {
    }

    /** A reorder: the rows are the folder's items, the tabs, an item's option groups or a group's options. */
    private static final Pattern ORDER = Pattern.compile("^.*/(folders/\\d+/items|tabs|items/\\d+/modifier-groups|modifier-groups/\\d+/options)/order$");
    private static final Pattern IDS = Pattern.compile("\"ids\"\\s*:\\s*\\[([^\\]]*)\\]");

    /**
     * {@code json}, with the current version of the edited row added when {@code request} is an edit that names none (or,
     * for a reorder, the current version of each listed row when it names no {@code versions}).
     */
    public static String stamped(JdbcTemplate jdbc, MockHttpServletRequestBuilder request, String json) {
        if (json == null) {
            return json;
        }
        var built = request.buildRequest(new MockServletContext());
        if ("PUT".equals(built.getMethod()) && !json.contains("\"versions\"")) {
            Matcher order = ORDER.matcher(built.getRequestURI());
            Matcher ids = IDS.matcher(json);
            if (order.matches() && ids.find()) {
                String rows = order.group(1);
                String table = rows.equals("tabs") ? "quickgrid_tab" : rows.startsWith("folders") ? "item"
                        : rows.startsWith("items") ? "modifier_group" : "modifier_option";
                StringBuilder versions = new StringBuilder();
                for (String id : ids.group(1).isBlank() ? new String[0] : ids.group(1).split(",")) {
                    List<Long> found = jdbc.queryForList("SELECT version FROM " + table + " WHERE id = ?", Long.class,
                            Long.parseLong(id.strip()));
                    versions.append(versions.isEmpty() ? "" : ",").append(found.isEmpty() ? 0 : found.get(0));
                }
                int end = json.lastIndexOf('}');
                return json.substring(0, end).stripTrailing() + ",\"versions\":[" + versions + "]" + json.substring(end);
            }
        }
        if (json.contains("\"version\"")) {
            return json;
        }
        Matcher edit = EDIT.matcher(built.getRequestURI());
        if (!"PUT".equals(built.getMethod()) || !edit.matches()) {
            return json;
        }
        List<Long> versions = jdbc.queryForList("SELECT version FROM " + TABLES.get(edit.group(1)) + " WHERE id = ?",
                Long.class, Long.parseLong(edit.group(2)));
        // A row that is not there has no version: the edit is answered 404 before any version is looked at.
        long version = versions.isEmpty() ? 0 : versions.get(0);
        int end = json.lastIndexOf('}');
        return json.substring(0, end).stripTrailing() + ",\"version\":" + version + json.substring(end);
    }
}
