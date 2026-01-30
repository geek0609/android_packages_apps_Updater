package net.pixelos.ota.controller;

import android.content.Context;
import android.util.Log;

import net.pixelos.ota.MirrorsDbHelper;
import net.pixelos.ota.model.UpdateInfo;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadMirrorController {

    private static final String TAG = "DownloadMirrorController";

    private static final Pattern SF_PROJECTS_PATTERN = Pattern.compile("sourceforge\\.net/projects/([^/]+)/files(/.+?)(?:/download)?$");
    private static final Pattern SF_MIRROR_PATTERN = Pattern.compile("\\.dl\\.sourceforge\\.net/project/([^/]+)(/.+)");

    private static Map<String, String> sMirrorLinks;
    private static Map<Double, String> sRankedMirrors;
    private static Map<String, String> sSortedMirrors;
    public static Map<Double, String> sSortedRankedMirrors;

    public static void setMirror(UpdateInfo updateInfo, Context context, String mirror) {
        MirrorsDbHelper mirrorsDbHelper = MirrorsDbHelper.getInstance(context);

        String mirrorUrl = updateInfo.getDownloadUrl();

        if (sSortedMirrors != null && !sSortedMirrors.isEmpty()) {
            for (Map.Entry<String, String> sortedMirrors : sSortedMirrors.entrySet()) {
                if (mirror.equals(sortedMirrors.getKey())) {
                    mirrorUrl = sortedMirrors.getValue();
                    mirrorsDbHelper.setMirrorUrl(sortedMirrors.getValue(), updateInfo.getDownloadId());
                    break;
                }
            }
        } else if (sMirrorLinks != null && !sMirrorLinks.isEmpty()) {
            for (Map.Entry<String, String> sfMirrors : sMirrorLinks.entrySet()) {
                if (mirror.equals(sfMirrors.getKey())) {
                    mirrorUrl = sfMirrors.getValue();
                    mirrorsDbHelper.setMirrorUrl(sfMirrors.getValue(), updateInfo.getDownloadId());
                    break;
                }
            }
        }

        mirrorsDbHelper.setMirrorName(mirror, updateInfo.getDownloadId());
        Log.d(TAG, "Mirror for: " + updateInfo.getName() + " set to " + mirrorUrl);
    }

    public static Map<String, String> fetchMirrors(UpdateInfo update) {
        sMirrorLinks = new LinkedHashMap<>();
        sRankedMirrors = new LinkedHashMap<>();
        sSortedMirrors = new LinkedHashMap<>();
        Map<String, String> rankLinks = new LinkedHashMap<>();

        String downloadUrl = update.getDownloadUrl();
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            Log.e(TAG, "Cannot fetch mirrors: download URL is null or empty");
            return null;
        }

        // Parse SourceForge URL to extract project name and filepath
        // Format 1: https://sourceforge.net/projects/{project}/files/{filepath}
        // Format 2: https://sourceforge.net/projects/{project}/files/{filepath}/download
        // Format 3: https://{mirror}.dl.sourceforge.net/project/{project}/{filepath}
        String projectName = null;
        String filepath = null;

        try {
            Matcher projectsMatcher = SF_PROJECTS_PATTERN.matcher(downloadUrl);
            Matcher mirrorMatcher = SF_MIRROR_PATTERN.matcher(downloadUrl);

            if (projectsMatcher.find()) {
                projectName = projectsMatcher.group(1);
                filepath = projectsMatcher.group(2);
            } else if (mirrorMatcher.find()) {
                projectName = mirrorMatcher.group(1);
                filepath = mirrorMatcher.group(2);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse URL: " + downloadUrl, e);
            return null;
        }

        if (projectName == null || filepath == null) {
            Log.e(TAG, "Could not extract project/filepath from URL: " + downloadUrl);
            return null;
        }

        Log.d(TAG, "Parsed from URL - Project: " + projectName + ", Filepath: " + filepath);

        String mirrorsUrl = "https://sourceforge.net/settings/mirror_choices?projectname=" + projectName + "&filename=" + filepath;
        Log.d(TAG, "Fetching mirrors from: " + mirrorsUrl);

        final String finalProjectName = projectName;
        final String finalFilepath = filepath;
        final Exception[] fetchException = {null};

        Thread mirrorFetch = new Thread(() -> {
            try {
                Log.d(TAG, "Starting mirror fetch request...");
                Document doc = Jsoup.connect(mirrorsUrl)
                        .userAgent("Mozilla/5.0")
                        .timeout(15000)
                        .get();
                Elements links = doc.select("#mirrorList li");

                Log.d(TAG, "Found " + links.size() + " mirror elements");

                if (links.isEmpty()) {
                    Log.w(TAG, "No mirrors found in response.");
                }

                for (Element link : links) {
                    String mirrorName = link.attr("id");
                    String mirrorPlace = link.text();
                    if (!mirrorName.equals("autoselect")) {
                        try {
                            mirrorPlace = mirrorPlace.substring(mirrorPlace.lastIndexOf("(") + 1,
                                            mirrorPlace.lastIndexOf(")"))
                                    .split(",", 2)[0]
                                    .trim();
                            sMirrorLinks.put(mirrorPlace, "https://" + mirrorName + ".dl.sourceforge.net/project/" + finalProjectName + finalFilepath);
                            rankLinks.put(mirrorPlace, "https://" + mirrorName + ".dl.sourceforge.net/project/" + finalProjectName + finalFilepath);
                            Log.d(TAG, "Mirror: " + mirrorName + " (" + mirrorPlace + ")");
                        } catch (StringIndexOutOfBoundsException e) {
                            Log.w(TAG, "Failed to parse mirror place for: " + mirrorName + ", text: " + mirrorPlace);
                        }
                    }
                }
                Log.d(TAG, "Successfully parsed " + sMirrorLinks.size() + " mirrors");
            } catch (IOException e) {
                Log.e(TAG, "Failed to fetch mirrors: " + e.getMessage(), e);
                fetchException[0] = e;
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error fetching mirrors: " + e.getMessage(), e);
                fetchException[0] = e;
            }
        });

        try {
            mirrorFetch.start();
            mirrorFetch.join();

            if (fetchException[0] != null) {
                Log.e(TAG, "Mirror fetch failed with exception: " + fetchException[0].getMessage());
                return null;
            }

            if (sMirrorLinks.isEmpty()) {
                Log.w(TAG, "No mirrors were found");
                return null;
            }

            return cookRankMirrorsData(rankLinks);
        } catch (InterruptedException e) {
            Log.e(TAG, "Mirror fetch thread interrupted!", e);
            return null;
        }
    }

    private static Map<String, String> cookRankMirrorsData(Map<String, String> rankLinks) {
        ExecutorService executor = Executors.newCachedThreadPool();

        for (Map.Entry<String, String> rlinks : rankLinks.entrySet()) {
            String rankUrl = rlinks.getValue();
            String rankName = rlinks.getKey();
            executor.execute(new RankMirrors(rankUrl, rankName));
        }

        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
            return sortMirrors(sRankedMirrors);
        } catch (InterruptedException e) {
            Log.e(TAG, "Executor interrupted!", e);
            return null;
        }
    }

    private static Map<String, String> sortMirrors(Map<Double, String> rankedMirrors) {
        sSortedRankedMirrors = new TreeMap<>(rankedMirrors);
        for (Map.Entry<Double, String> rankLinks : sSortedRankedMirrors.entrySet()) {
            for (Map.Entry<String, String> mirrorLinks : sMirrorLinks.entrySet()) {
                if (rankLinks.getValue().equals(mirrorLinks.getKey())) {
                    sSortedMirrors.put(rankLinks.getValue(), mirrorLinks.getValue());
                    Log.d(TAG, "Sorted mirrors list: " + rankLinks.getValue());
                }
            }
        }
        return sSortedMirrors;
    }

    private static class RankMirrors implements Runnable {
        private final String rankUrl;
        private final String rankName;

        RankMirrors(String rankUrl, String rankName) {
            this.rankUrl = rankUrl;
            this.rankName = rankName;
        }

        @Override
        public void run() {
            // 512KB chunk size
            final int CHUNK_SIZE = 524288;

            HttpURLConnection connection = null;
            InputStream inputStream = null;
            try {
                URL url = new URL(rankUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Range", "bytes=0-" + (CHUNK_SIZE - 1));
                connection.setRequestProperty("Connection", "close");
                connection.setInstanceFollowRedirects(true);

                connection.connect();

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_PARTIAL || responseCode == HttpURLConnection.HTTP_OK) {
                    inputStream = connection.getInputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    int totalBytesRead = 0;

                    long start = System.nanoTime();

                    while (totalBytesRead < CHUNK_SIZE && (bytesRead = inputStream.read(buffer)) != -1) {
                        totalBytesRead += bytesRead;
                    }

                    long durationNs = System.nanoTime() - start;

                    if (totalBytesRead > 10240) {
                        double durationSec = durationNs / 1_000_000_000.0;
                        double sizeMb = totalBytesRead / (1024.0 * 1024.0);

                        double speedMbPerSec = sizeMb / durationSec;
                        double msPerMb = (durationSec * 1000.0) / sizeMb;

                        synchronized (DownloadMirrorController.class) {
                            sRankedMirrors.put(msPerMb, rankName);
                        }
                        Log.d(TAG, "Speed of mirror " + rankName + ": " + String.format("%.2f", speedMbPerSec) + " MB/s");
                    } else {
                        Log.w(TAG, "Mirror " + rankName + " returned too little data: " + totalBytesRead + " bytes");
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to test speed for " + rankName + ": " + e.getMessage());
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException ignored) {}
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }
}
