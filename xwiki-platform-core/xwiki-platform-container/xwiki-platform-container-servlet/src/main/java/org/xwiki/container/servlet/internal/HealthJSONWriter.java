/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.container.servlet.internal;

import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.xwiki.container.health.Readiness;
import org.xwiki.container.health.Readiness.State;

/**
 * Hand written JSON serialization for the health endpoint, to avoid depending on a JSON library in the servlet
 * container module.
 *
 * @version $Id$
 * @since 18.10.0RC1
 */
public final class HealthJSONWriter
{
    private static final String JSON_READY = ",\"ready\":";

    private static final String JSON_NULL = "null";

    private HealthJSONWriter()
    {
        // Utility class
    }

    /**
     * @return the body of the liveness answer
     */
    public static String toLivenessJSON()
    {
        return "{\"status\":\"alive\"}";
    }

    /**
     * @param state the state
     * @return the JSON name of the state
     */
    public static String toJSONName(State state)
    {
        return state.name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param status the aggregated status
     * @param ready the aggregated ready flag
     * @param checks the readiness of each indicator, by hint
     * @param details true to expose the details of each check and the error messages, false to only expose the state,
     *            the ready flag and the class name of the errors
     * @return the body of the readiness answer
     */
    public static String toReadinessJSON(String status, boolean ready, Map<String, Readiness> checks, boolean details)
    {
        StringBuilder json = new StringBuilder();
        json.append("{\"status\":");
        appendString(json, status);
        json.append(JSON_READY).append(ready);
        json.append(",\"checks\":{");
        boolean first = true;
        for (Map.Entry<String, Readiness> entry : checks.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendString(json, entry.getKey());
            json.append(":{\"state\":");
            appendString(json, toJSONName(entry.getValue().state()));
            json.append(JSON_READY).append(entry.getValue().ready());
            appendDetails(json, entry.getValue().details(), details);
            json.append('}');
        }
        json.append("}}");

        return json.toString();
    }

    private static void appendDetails(StringBuilder json, Map<String, Object> map, boolean details)
    {
        Object error = map.get(Readiness.DETAIL_ERROR);

        if (details) {
            json.append(",\"details\":{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                appendString(json, entry.getKey());
                json.append(':');
                if (entry.getValue() instanceof Throwable throwable) {
                    appendString(json, ExceptionUtils.getRootCauseMessage(throwable));
                } else {
                    appendValue(json, entry.getValue());
                }
            }
            json.append('}');
        } else if (error instanceof Throwable throwable) {
            // Only expose the type of the error, the message might contain sensitive information
            json.append(",\"error\":");
            appendString(json, ExceptionUtils.getRootCause(throwable).getClass().getName());
        }
    }

    private static void appendValue(StringBuilder json, Object value)
    {
        if (value == null) {
            json.append(JSON_NULL);
        } else if (value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof Number number) {
            appendNumber(json, number);
        } else {
            appendString(json, value.toString());
        }
    }

    private static void appendNumber(StringBuilder json, Number number)
    {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                // Not representable in JSON
                json.append(JSON_NULL);
            } else {
                json.append(value);
            }
        } else {
            json.append(number);
        }
    }

    private static void appendString(StringBuilder json, String value)
    {
        json.append('"');
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
            }
        }
        json.append('"');
    }
}
