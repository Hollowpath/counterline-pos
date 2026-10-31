package com.counterline.pos.staff;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A staff edit carries the version of the person it was made from. A test that just wants to make an edit has its body
 * stamped with the person's current version here (as the dashboard form carries the version it loaded); a body that
 * already names a version is left alone, which is how the stale-form tests send an old one.
 */
public final class StaffVersions {

    private static final Pattern EDIT = Pattern.compile("^.*/admin/staff/(\\d+)$");

    private StaffVersions() {
    }

    public static String stamped(JdbcTemplate jdbc, MockHttpServletRequestBuilder request, String json) {
        if (json == null || json.contains("\"version\"")) {
            return json;
        }
        var built = request.buildRequest(new MockServletContext());
        Matcher edit = EDIT.matcher(built.getRequestURI());
        if (!"PUT".equals(built.getMethod()) || !edit.matches()) {
            return json;
        }
        List<Long> versions = jdbc.queryForList("SELECT version FROM staff WHERE id = ?", Long.class, Long.parseLong(edit.group(1)));
        // A person who is not there has no version: the edit is answered 404 before any version is looked at.
        long version = versions.isEmpty() ? 0 : versions.get(0);
        int end = json.lastIndexOf('}');
        return json.substring(0, end).stripTrailing() + ",\"version\":" + version + json.substring(end);
    }
}
