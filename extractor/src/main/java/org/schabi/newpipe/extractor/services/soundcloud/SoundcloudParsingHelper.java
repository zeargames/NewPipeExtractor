package org.schabi.newpipe.extractor.services.soundcloud;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.schabi.newpipe.extractor.DownloadResponse;
import org.schabi.newpipe.extractor.Downloader;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemsCollector;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.utils.Parser;
import org.schabi.newpipe.extractor.utils.Parser.RegexException;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;

import static org.schabi.newpipe.extractor.utils.Utils.replaceHttpWithHttps;

public class SoundcloudParsingHelper {
    private static final String HARDCODED_CLIENT_ID = "bkcJLoXNaiFlsLaKBQXOxO5FhW0NJVnu"; // Updated on 29/11/19
    private static String clientId;
    
    private SoundcloudParsingHelper() {
    }

    public static String clientId() throws ExtractionException, IOException {
        if (clientId != null && !clientId.isEmpty()) return clientId;

        Downloader dl = NewPipe.getDownloader();
        clientId = HARDCODED_CLIENT_ID;
        if (checkIfHardcodedClientIdIsValid(dl)) {
            return clientId;
        }

        final DownloadResponse download = dl.get("https://soundcloud.com");
        String response = download.getResponseBody();
        final String clientIdPattern = ",client_id:\"(.*?)\"";

        Document doc = Jsoup.parse(response);
        final Elements possibleScripts = doc.select("script[src*=\"sndcdn.com/assets/\"][src$=\".js\"]");
        // The one containing the client id will likely be the last one
        Collections.reverse(possibleScripts);

        final HashMap<String, String> headers = new HashMap<>();
        headers.put("Range", "bytes=0-16384");

        for (Element element : possibleScripts) {
            final String srcUrl = element.attr("src");
            if (srcUrl != null && !srcUrl.isEmpty()) {
                try {
                    return clientId = Parser.matchGroup1(clientIdPattern, dl.download(srcUrl, headers));
                } catch (RegexException ignored) {
                    // Ignore it and proceed to try searching other script
                }
            }
        }

        // Officially give up
        throw new ExtractionException("Couldn't extract client id");
    }

    static boolean checkIfHardcodedClientIdIsValid(Downloader dl) {
        final String apiUrl = "https://api.soundcloud.com/connect?client_id=" + HARDCODED_CLIENT_ID;
        try {
            // Should return 200 to indicate that the client id is valid, a 401 is returned otherwise.
            return dl.head(apiUrl).getResponseCode() == 200;
        } catch (Exception ignored) {
            // No need to throw an exception here. If something went wrong, the client_id is wrong
            return false;
        }

    }

    public static String toDateString(String time) throws ParsingException {
        try {
            Date date;
            // Have two date formats, one for the 'api.soundc...' and the other 'api-v2.soundc...'.
            try {
                date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").parse(time);
            } catch (Exception e) {
                date = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss +0000").parse(time);
            }

            SimpleDateFormat newDateFormat = new SimpleDateFormat("yyyy-MM-dd");
            return newDateFormat.format(date);
        } catch (ParseException e) {
            throw new ParsingException(e.getMessage(), e);
        }
    }

    /**
     * Call the endpoint "/resolve" of the api.<p>
     * 
     * See https://developers.soundcloud.com/docs/api/reference#resolve
     */
    public static JsonObject resolveFor(Downloader downloader, String url) throws IOException, ExtractionException {
        String apiUrl = "https://api.soundcloud.com/resolve"
                + "?url=" + URLEncoder.encode(url, "UTF-8")
                + "&client_id=" + clientId();

        try {
            return JsonParser.object().from(downloader.download(apiUrl));
        } catch (JsonParserException e) {
            throw new ParsingException("Could not parse json response", e);
        }
    }

    /**
     * Fetch the embed player with the apiUrl and return the canonical url (like the permalink_url from the json api).
     *
     * @return the url resolved
     */
    public static String resolveUrlWithEmbedPlayer(String apiUrl) throws IOException, ReCaptchaException, ParsingException {

        String response = NewPipe.getDownloader().download("https://w.soundcloud.com/player/?url="
                + URLEncoder.encode(apiUrl, "UTF-8"));

        return Jsoup.parse(response).select("link[rel=\"canonical\"]").first().attr("abs:href");
    }

    /**
     * Fetch the embed player with the url and return the id (like the id from the json api).
     *
     * @return the resolved id
     */
    public static String resolveIdWithEmbedPlayer(String url) throws IOException, ReCaptchaException, ParsingException {

        String response = NewPipe.getDownloader().download("https://w.soundcloud.com/player/?url="
                + URLEncoder.encode(url, "UTF-8"));
        // handle playlists / sets different and get playlist id via uir field in JSON
        if (url.contains("sets") && !url.endsWith("sets") && !url.endsWith("sets/"))
            return Parser.matchGroup1("\"uri\":\\s*\"https:\\/\\/api\\.soundcloud\\.com\\/playlists\\/((\\d)*?)\"", response);
        return Parser.matchGroup1(",\"id\":(([^}\\n])*?),", response);
    }

    /**
     * Fetch the users from the given api and commit each of them to the collector.
     * <p>
     * This differ from {@link #getUsersFromApi(ChannelInfoItemsCollector, String)} in the sense that they will always
     * get MIN_ITEMS or more.
     *
     * @param minItems the method will return only when it have extracted that many items (equal or more)
     */
    public static String getUsersFromApiMinItems(int minItems, ChannelInfoItemsCollector collector, String apiUrl) throws IOException, ReCaptchaException, ParsingException {
        String nextPageUrl = SoundcloudParsingHelper.getUsersFromApi(collector, apiUrl);

        while (!nextPageUrl.isEmpty() && collector.getItems().size() < minItems) {
            nextPageUrl = SoundcloudParsingHelper.getUsersFromApi(collector, nextPageUrl);
        }

        return nextPageUrl;
    }

    /**
     * Fetch the user items from the given api and commit each of them to the collector.
     *
     * @return the next streams url, empty if don't have
     */
    public static String getUsersFromApi(ChannelInfoItemsCollector collector, String apiUrl) throws IOException, ReCaptchaException, ParsingException {
        String response = NewPipe.getDownloader().download(apiUrl);
        JsonObject responseObject;
        try {
            responseObject = JsonParser.object().from(response);
        } catch (JsonParserException e) {
            throw new ParsingException("Could not parse json response", e);
        }

        JsonArray responseCollection = responseObject.getArray("collection");
        for (Object o : responseCollection) {
            if (o instanceof JsonObject) {
                JsonObject object = (JsonObject) o;
                collector.commit(new SoundcloudChannelInfoItemExtractor(object));
            }
        }

        String nextPageUrl;
        try {
            nextPageUrl = responseObject.getString("next_href");
            if (!nextPageUrl.contains("client_id=")) nextPageUrl += "&client_id=" + SoundcloudParsingHelper.clientId();
        } catch (Exception ignored) {
            nextPageUrl = "";
        }

        return nextPageUrl;
    }

    /**
     * Fetch the streams from the given api and commit each of them to the collector.
     * <p>
     * This differ from {@link #getStreamsFromApi(StreamInfoItemsCollector, String)} in the sense that they will always
     * get MIN_ITEMS or more items.
     *
     * @param minItems the method will return only when it have extracted that many items (equal or more)
     */
    public static String getStreamsFromApiMinItems(int minItems, StreamInfoItemsCollector collector, String apiUrl) throws IOException, ReCaptchaException, ParsingException {
        String nextPageUrl = SoundcloudParsingHelper.getStreamsFromApi(collector, apiUrl);

        while (!nextPageUrl.isEmpty() && collector.getItems().size() < minItems) {
            nextPageUrl = SoundcloudParsingHelper.getStreamsFromApi(collector, nextPageUrl);
        }

        return nextPageUrl;
    }

    /**
     * Fetch the streams from the given api and commit each of them to the collector.
     *
     * @return the next streams url, empty if don't have
     */
    public static String getStreamsFromApi(StreamInfoItemsCollector collector, String apiUrl, boolean charts) throws IOException, ReCaptchaException, ParsingException {
        String response = NewPipe.getDownloader().download(apiUrl);
        JsonObject responseObject;
        try {
            responseObject = JsonParser.object().from(response);
        } catch (JsonParserException e) {
            throw new ParsingException("Could not parse json response", e);
        }

        JsonArray responseCollection = responseObject.getArray("collection");
        for (Object o : responseCollection) {
            if (o instanceof JsonObject) {
                JsonObject object = (JsonObject) o;
                collector.commit(new SoundcloudStreamInfoItemExtractor(charts ? object.getObject("track") : object));
            }
        }

        String nextPageUrl;
        try {
            nextPageUrl = responseObject.getString("next_href");
            if (!nextPageUrl.contains("client_id=")) nextPageUrl += "&client_id=" + SoundcloudParsingHelper.clientId();
        } catch (Exception ignored) {
            nextPageUrl = "";
        }

        return nextPageUrl;
    }

    public static String getStreamsFromApi(StreamInfoItemsCollector collector, String apiUrl) throws ReCaptchaException, ParsingException, IOException {
        return getStreamsFromApi(collector, apiUrl, false);
    }

    @Nonnull
    static String getUploaderUrl(JsonObject object) {
        String url = object.getObject("user").getString("permalink_url", "");
        return replaceHttpWithHttps(url);
    }

    @Nonnull
    static String getAvatarUrl(JsonObject object) {
        String url = object.getObject("user", new JsonObject()).getString("avatar_url", "");
        return replaceHttpWithHttps(url);
    }

    public static String getUploaderName(JsonObject object) {
        return object.getObject("user").getString("username", "");
    }
}
