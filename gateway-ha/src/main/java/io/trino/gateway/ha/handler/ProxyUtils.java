/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.handler;

import com.google.common.base.Splitter;
import com.google.common.io.CharStreams;
import io.airlift.log.Logger;
import io.trino.gateway.ha.router.QueryHistoryManager;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Strings.isNullOrEmpty;
import static io.trino.gateway.ha.handler.QueryIdCachingProxyHandler.PROXY_TARGET_HEADER;
import static io.trino.gateway.ha.handler.QueryIdCachingProxyHandler.TRINO_UI_PATH;
import static io.trino.gateway.ha.handler.QueryIdCachingProxyHandler.USER_HEADER;
import static io.trino.gateway.ha.handler.QueryIdCachingProxyHandler.V1_QUERY_PATH;
import static io.trino.gateway.ha.handler.QueryIdCachingProxyHandler.V1_STATEMENT_PATH;

public final class ProxyUtils
{
    public static final String SOURCE_HEADER = "X-Trino-Source";
    public static final String AUTHORIZATION = "Authorization";

    private static final Logger log = Logger.get(ProxyUtils.class);
    private static final int QUERY_TEXT_LENGTH_FOR_HISTORY = 200;
    /**
     * This regular expression matches query ids as they appear in the path of a URL. The query id must be preceded
     * by a "/". A query id is defined as three groups of digits separated by underscores, with a final group
     * consisting of any alphanumeric characters.
     */
    private static final Pattern QUERY_ID_PATH_PATTERN = Pattern.compile(".*/(\\d+_\\d+_\\d+_\\w+).*");
    /**
     * This regular expression matches query ids as they appear in the query parameters of a URL. The query id is
     * defined as in QUERY_TEXT_LENGTH_FOR_HISTORY. The query id must either be at the beginning of the query parameter
     * string, or be preceded by %2F (a URL-encoded "/"), or  "query_id=", with or without the underscore and any
     * capitalization.
     */
    private static final Pattern QUERY_ID_PARAM_PATTERN = Pattern.compile(".*(?:%2F|(?i)query_?id(?-i)=|^)(\\d+_\\d+_\\d+_\\w+).*");
    private static final Pattern EXTRACT_BETWEEN_SINGLE_QUOTES = Pattern.compile("'([^\\s']+)'");

    private ProxyUtils() {}

    public static QueryHistoryManager.QueryDetail getQueryDetailsFromRequest(HttpServletRequest request)
            throws IOException
    {
        QueryHistoryManager.QueryDetail queryDetail = new QueryHistoryManager.QueryDetail();
        queryDetail.setBackendUrl(request.getHeader(PROXY_TARGET_HEADER));
        queryDetail.setCaptureTime(System.currentTimeMillis());
        queryDetail.setUser(getQueryUser(request));
        queryDetail.setSource(request.getHeader(SOURCE_HEADER));
        String queryText = CharStreams.toString(request.getReader());
        queryDetail.setQueryText(
                queryText.length() > QUERY_TEXT_LENGTH_FOR_HISTORY
                        ? queryText.substring(0, QUERY_TEXT_LENGTH_FOR_HISTORY) + "..."
                        : queryText);
        return queryDetail;
    }

    public static String getQueryUser(HttpServletRequest request)
    {
        String trinoUser = request.getHeader(USER_HEADER);

        if (!isNullOrEmpty(trinoUser)) {
            log.info("user from %s", USER_HEADER);
            return trinoUser;
        }

        log.info("user from basic auth");
        String user = "";
        String header = request.getHeader(AUTHORIZATION);
        if (header == null) {
            log.error("didn't find any basic auth header");
            return user;
        }

        int space = header.indexOf(' ');
        if ((space < 0) || !header.substring(0, space).equalsIgnoreCase("basic")) {
            log.error("basic auth format is incorrect");
            return user;
        }

        String headerInfo = header.substring(space + 1).trim();
        if (isNullOrEmpty(headerInfo)) {
            log.error("The encoded value of basic auth doesn't exist");
            return user;
        }

        String info = new String(Base64.getDecoder().decode(headerInfo));
        List<String> parts = Splitter.on(':').limit(2).splitToList(info);
        if (parts.size() < 1) {
            log.error("no user inside the basic auth text");
            return user;
        }
        return parts.get(0);
    }

    public static String extractQueryIdIfPresent(HttpServletRequest request)
    {
        String path = request.getRequestURI();
        String queryParams = request.getQueryString();
        try {
            String queryText = CharStreams.toString(request.getReader());
            if (!isNullOrEmpty(queryText)
                    && queryText.toLowerCase().contains("system.runtime.kill_query")) {
                // extract and return the queryId
                String[] parts = queryText.split(",");
                for (String part : parts) {
                    if (part.contains("query_id")) {
                        Matcher matcher = EXTRACT_BETWEEN_SINGLE_QUOTES.matcher(part);
                        if (matcher.find()) {
                            String queryQuoted = matcher.group();
                            if (!isNullOrEmpty(queryQuoted) && queryQuoted.length() > 0) {
                                return queryQuoted.substring(1, queryQuoted.length() - 1);
                            }
                        }
                    }
                }
            }
        }
        catch (Exception e) {
            log.error(e, "Error extracting query payload from request");
        }

        return extractQueryIdIfPresent(path, queryParams);
    }

    public static String extractQueryIdIfPresent(String path, String queryParams)
    {
        if (path == null) {
            return null;
        }
        String queryId = null;

        log.debug("Trying to extract query id from path [%s] or queryString [%s]", path, queryParams);
        if (path.startsWith(V1_STATEMENT_PATH) || path.startsWith(V1_QUERY_PATH)) {
            String[] tokens = path.split("/");
            if (tokens.length >= 4) {
                if (path.contains("queued")
                        || path.contains("scheduled")
                        || path.contains("executing")
                        || path.contains("partialCancel")) {
                    queryId = tokens[4];
                }
                else {
                    queryId = tokens[3];
                }
            }
        }
        else if (path.startsWith(TRINO_UI_PATH)) {
            Matcher matcher = QUERY_ID_PATH_PATTERN.matcher(path);
            if (matcher.matches()) {
                queryId = matcher.group(1);
            }
        }
        if (!isNullOrEmpty(queryParams)) {
            Matcher matcher = QUERY_ID_PARAM_PATTERN.matcher(queryParams);
            if (matcher.matches()) {
                queryId = matcher.group(1);
            }
        }
        log.debug("Query id in URL [%s]", queryId);
        return queryId;
    }

    public static String buildUriWithNewBackend(String backendHost, HttpServletRequest request)
    {
        return backendHost + request.getRequestURI() + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
    }
}
