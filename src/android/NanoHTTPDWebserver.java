package org.apache.cordova.plugin;


import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;

public class NanoHTTPDWebserver extends NanoHTTPD {

    Webserver webserver;

    public NanoHTTPDWebserver(int port, Webserver webserver) {
        super(port);
        this.webserver = webserver;
    }

    private String getBodyText(IHTTPSession session) {
        Map<String, String> files = new HashMap<String, String>();
        Method method = session.getMethod();
        if (Method.PUT.equals(method) || Method.POST.equals(method)) {
            try {
                session.parseBody(files);
            } catch (IOException ioe) {
                return "{}";
            } catch (ResponseException re) {
                return "{}";
            }
        }
        // get the POST body
        return files.get("postData");
    }

    /**
     * Create a request object
     * <p>
     * [
     * "requestId": requestUUID,
     * "      body": request.jsonObject ?? "",
     * "      headers": request.headers,
     * "      method": request.method,
     * "      path": request.url.path,
     * "      query": request.url.query ?? ""
     * ]
     *
     * @param session
     * @return
     */
    private JSONObject createJSONRequest(String requestId, IHTTPSession session) throws JSONException {
        JSONObject jsonRequest = new JSONObject();
        jsonRequest.put("requestId", requestId);
        jsonRequest.put("body", this.getBodyText(session));
        jsonRequest.put("headers", session.getHeaders());
        jsonRequest.put("method", session.getMethod());
        jsonRequest.put("path", session.getUri());
        jsonRequest.put("query", session.getQueryParameterString());
        return jsonRequest;
    }

    private String getContentType(JSONObject responseObject) throws JSONException {
        if (responseObject.has("headers") &&
                responseObject.getJSONObject("headers").has("Content-Type")) {
            return responseObject.getJSONObject("headers").getString("Content-Type");
        } else {
            return "text/plain";
        }
    }

    private Response newFixedFileResponse(File file, String mime) throws FileNotFoundException {
        Response res;
        res = newFixedLengthResponse(Response.Status.OK, mime, new FileInputStream(file), (int) file.length());
        res.addHeader("Accept-Ranges", "bytes");
        return res;
    }

    Response serveFile(Map<String, String> header, File file, String mime) {
        Response res;
        try {
            // Calculate etag
            String etag = Integer.toHexString((file.getAbsolutePath() + file.lastModified() + "" + file.length()).hashCode());

            // Support (simple) skipping:
            long startFrom = 0;
            long endAt = -1;
            String range = header.get("range");
            if (range != null) {
                if (range.startsWith("bytes=")) {
                    range = range.substring("bytes=".length());
                    int minus = range.indexOf('-');
                    try {
                        if (minus > 0) {
                            startFrom = Long.parseLong(range.substring(0, minus));
                            endAt = Long.parseLong(range.substring(minus + 1));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // get if-range header. If present, it must match etag or else we
            // should ignore the range request
            String ifRange = header.get("if-range");
            boolean headerIfRangeMissingOrMatching = (ifRange == null || etag.equals(ifRange));

            String ifNoneMatch = header.get("if-none-match");
            boolean headerIfNoneMatchPresentAndMatching = ifNoneMatch != null && ("*".equals(ifNoneMatch) || ifNoneMatch.equals(etag));

            // Change return code and add Content-Range header when skipping is
            // requested
            long fileLen = file.length();

            if (headerIfRangeMissingOrMatching && range != null && startFrom >= 0 && startFrom < fileLen) {
                // range request that matches current etag
                // and the startFrom of the range is satisfiable
                if (headerIfNoneMatchPresentAndMatching) {
                    // range request that matches current etag
                    // and the startFrom of the range is satisfiable
                    // would return range from file
                    // respond with not-modified
                    res = newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, "");
                    res.addHeader("ETag", etag);
                } else {
                    if (endAt < 0) {
                        endAt = fileLen - 1;
                    }
                    long newLen = endAt - startFrom + 1;
                    if (newLen < 0) {
                        newLen = 0;
                    }

                    FileInputStream fis = new FileInputStream(file);
                    fis.skip(startFrom);

                    res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, newLen);
                    res.addHeader("Accept-Ranges", "bytes");
                    res.addHeader("Content-Length", "" + newLen);
                    res.addHeader("Content-Range", "bytes " + startFrom + "-" + endAt + "/" + fileLen);
                    res.addHeader("ETag", etag);
                }
            } else {

                if (headerIfRangeMissingOrMatching && range != null && startFrom >= fileLen) {
                    // return the size of the file
                    // 4xx responses are not trumped by if-none-match
                    res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, NanoHTTPD.MIME_PLAINTEXT, "");
                    res.addHeader("Content-Range", "bytes */" + fileLen);
                    res.addHeader("ETag", etag);
                } else if (range == null && headerIfNoneMatchPresentAndMatching) {
                    // full-file-fetch request
                    // would return entire file
                    // respond with not-modified
                    res = newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, "");
                    res.addHeader("ETag", etag);
                } else if (!headerIfRangeMissingOrMatching && headerIfNoneMatchPresentAndMatching) {
                    // range request that doesn't match current etag
                    // would return entire (different) file
                    // respond with not-modified

                    res = newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, "");
                    res.addHeader("ETag", etag);
                } else {
                    // supply the file
                    res = newFixedFileResponse(file, mime);
                    res.addHeader("Content-Length", "" + fileLen);
                    res.addHeader("ETag", etag);
                }
            }
        } catch (IOException ioe) {
            res = newFixedLengthResponse(Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, ioe.getMessage());
        }

        return res;
    }

    /**
     * Get mime type based of file extension
     * @param url
     * @return
     */
    public static String getMimeType(String url) {
        String type = null;
        String extension = null;
        int i = url.lastIndexOf(".");
        if (i > 0) {
            extension = url.substring(i + 1);
        }
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        if (type == null) {
            type = fixAndroidsProblemOfMissingMimeTypes(extension);
        }
        return type;
    }

    @Override
    public Response serve(IHTTPSession session) {
        Log.d(this.getClass().getName(), "New request is incoming!");

        String requestUUID = UUID.randomUUID().toString();

        PluginResult pluginResult = null;
        try {
            pluginResult = new PluginResult(
                    PluginResult.Status.OK, this.createJSONRequest(requestUUID, session));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        pluginResult.setKeepCallback(true);
        this.webserver.onRequestCallbackContext.sendPluginResult(pluginResult);

        while (!this.webserver.responses.containsKey(requestUUID)) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        JSONObject responseObject = (JSONObject) this.webserver.responses.get(requestUUID);
        Response response = null;
        Log.d(this.getClass().getName(), "responseObject: " + responseObject.toString());

        try {
            if (responseObject.has("path")) {
                File file = new File(responseObject.getString("path"));
                Uri uri = Uri.fromFile(file);
                String mime = getMimeType(uri.toString());
                response = serveFile(session.getHeaders(), file, mime);
            } else {
                response = newFixedLengthResponse(
                    Response.Status.lookup(responseObject.getInt("status")),
                    getContentType(responseObject),
                    responseObject.getString("body")
                );
            }
            Iterator<?> keys = responseObject.getJSONObject("headers").keys();
            while (keys.hasNext()) {
                String key = (String) keys.next();
                response.addHeader(
                    key,
                    responseObject.getJSONObject("headers").getString(key)
                );
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return response;
    }

    private static String fixAndroidsProblemOfMissingMimeTypes(String extension) {
        switch (extension) {
            case "aac":
                return "audio/aac"; // AAC audio
            case "abw":
                return "application/x-abiword"; // AbiWord document
            case "arc":
                return "application/x-freearc"; // Archive document (multiple files embedded)
            case "avi":
                return "video/x-msvideo"; // AVI: Audio Video Interleave
            case "azw":
                return "application/vnd.amazon.ebook"; // Amazon Kindle eBook format
            case "bin":
                return "application/octet-stream"; // Any kind of binary data
            case "bmp":
                 return "image/bmp"; // Windows OS/2 Bitmap Graphics
            case "bz":
                return "application/x-bzip"; // BZip archive
            case "bz2":
                return "application/x-bzip2"; // BZip2 archive
            case "csh":
                return "application/x-csh"; // C-Shell script
            case "css":
                return "text/css"; // Cascading Style Sheets (CSS)
            case "csv":
                return "text/csv"; // Comma-separated values (CSV)
            case "doc":
                return "application/msword"; // Microsoft Word
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; // Microsoft Word (OpenXML)
            case "eot":
                return "application/vnd.ms-fontobject"; // MS Embedded OpenType fonts
            case "epub":
                return "application/epub+zip"; // Electronic publication (EPUB)
            case "gz":
                return "application/gzip"; // GZip Compressed Archive
            case "gif":
                return "image/gif"; // Graphics Interchange Format (GIF)
            case "htm":
            case "html":
                return "text/html"; // HyperText Markup Language (HTML)
            case "ico":
                return "image/vnd.microsoft.icon"; // Icon format
            case "ics":
                return "text/calendar"; // iCalendar format
            case "jar":
                return "application/java-archive"; // Java Archive (JAR)
            case "jpeg":
            case "jpg":
                return "image/jpeg"; // JPEG images
            case "js":
                return "text/javascript"; // JavaScript
            case "json":
                return "application/json"; // JSON format
            case "jsonld":
                return "application/ld+json"; // JSON-LD format
            case "mid":
                return "audio/midi audio/x-midi"; // Musical Instrument Digital Interface (MIDI)
            case "midi":
                return "audio/midi audio/x-midi"; // Musical Instrument Digital Interface (MIDI)
            case "mjs":
                return "text/javascript"; // JavaScript module
            case "mp3":
                return "audio/mpeg"; // MP3 audio
            case "mpeg":
                return "video/mpeg"; // MPEG Video
            case "mpkg":
                return "application/vnd.apple.installer+xml"; // Apple Installer Package
            case "odp":
                return "application/vnd.oasis.opendocument.presentation"; // OpenDocument presentation document
            case "ods":
                return "application/vnd.oasis.opendocument.spreadsheet"; // OpenDocument spreadsheet document
            case "odt":
                return "application/vnd.oasis.opendocument.text"; // OpenDocument text document
            case "oga":
            case "ogv":
                return "video/ogg"; // OGG video
            case "ogx":
                return "application/ogg"; // OGG
            case "opus":
                return "audio/opus"; // Opus audio
            case "otf":
                return "font/otf"; // OpenType font
            case "png":
                return "image/png"; // Portable Network Graphics
            case "pdf":
                return "application/pdf"; // Adobe Portable Document Format (PDF)
            case "php":
                return "application/php"; // Hypertext Preprocessor (Personal Home Page)
            case "ppt":
                return "application/vnd.ms-powerpoint"; // Microsoft PowerPoint
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation"; // Microsoft PowerPoint (OpenXML)
            case "rar":
                return "application/vnd.rar"; // RAR archive
            case "rtf":
                return "application/rtf"; // Rich Text Format (RTF)
            case "sh":
                return "application/x-sh"; // Bourne shell script
            case "svg":
                return "image/svg+xml"; // Scalable Vector Graphics (SVG)
            case "swf":
                return "application/x-shockwave-flash"; // Small web format (SWF) or Adobe Flash document
            case "tar":
                return "application/x-tar"; // Tape Archive (TAR)
            case "tif":
            case "tiff":
                return "image/tiff"; // Tagged Image File Format (TIFF)
            case "ts":
                return "video/mp2t"; // MPEG transport stream
            case "ttf":
                return "font/ttf"; // TrueType Font
            case "txt":
                return "text/plain"; // Text, (generally ASCII or ISO 8859-n)
            case "vsd":
                return "application/vnd.visio"; // Microsoft Visio
            case "wav":
                return "audio/wav"; // Waveform Audio Format
            case "weba":
                return "audio/webm"; // WEBM audio
            case "webm":
                return "video/webm"; // WEBM video
            case "webp":
                return "image/webp"; // WEBP image
            case "woff":
                return "font/woff"; // Web Open Font Format (WOFF)
            case "woff2":
                return "font/woff2"; // Web Open Font Format (WOFF)
            case "xhtml":
                return "application/xhtml+xml"; // XHTML
            case "xls":
                return "application/vnd.ms-excel"; // Microsoft Excel
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; // Microsoft Excel (OpenXML)
            case "xml":
                return "application/xml"; // XML
            case "xul":
                return "application/vnd.mozilla.xul+xml"; // XUL
            case "zip":
                return "application/zip"; // ZIP archive
            case "3gp":
                return "video/3gpp"; // 3GPP audio/video container
            case "3g2":
                return "video/3gpp2"; // 3GPP2 audio/video container
            case "7z":
                return "application/x-7z-compressed"; // 7-zip archive
        }
        return null;
    }
}
