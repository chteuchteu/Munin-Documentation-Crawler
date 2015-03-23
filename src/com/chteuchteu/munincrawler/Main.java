package com.chteuchteu.munincrawler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import com.google.gson.Gson;
import com.googlecode.htmlcompressor.compressor.HtmlCompressor;

public class Main {
    public static final String CORE_PLUGINS_HOME = "http://gallery.munin-monitoring.org/index.html";
    public static final String THIRDP_PLUGINS_HOME = "http://gallery.munin-monitoring.org/contrib/index.html";

    public static final String FILES_DIRECTORY = "output/";
    public static final String FILES_DIRECTORY_old = "output2/";

    private static enum ParseType { CORE, TIRDP }
    private static enum LaunchMode { FETCH_DOCUMENTATION, DISCOVER_PLUGINS }

    private static Index index;
    private static HtmlCompressor compressor = new HtmlCompressor();
    private static final boolean COMPRESS_HTML_FILES = false;
    private static final boolean INCLUDE_THIRD_PARTY_PLUGINS = false;

    public static void main(String [] args) {
        LaunchMode launchMode = LaunchMode.FETCH_DOCUMENTATION;

        switch (launchMode) {
            case DISCOVER_PLUGINS:
                String[] servers = { "http://demo.munin-monitoring.org/", "http://munin.ping.uio.no/" };

                List<String> plugins = new ArrayList<String>();

                for (String server : servers) {
                    log("Polling server " + server);
                    Document doc = null;

                    try {
                        doc = Jsoup.connect(server).get();
                    } catch (Exception ex) { ex.printStackTrace(); }

                    if (doc == null) {
                        log("    Fail, continuing.");
                        continue;
                    }

                    // Find hosts
                    Elements hosts = doc.select("span.host a");
                    for (Element host : hosts) {
                        log("        Found host " + host.text());

                        Document hostDoc = null;

                        try {
                            hostDoc = Jsoup.connect(host.attr("abs:href")).get();
                        } catch (Exception ex) { ex.printStackTrace(); }

                        if (hostDoc == null) {
                            log("            HostDoc fail, continuing.");
                            continue;
                        }

                        // Find plugins
                        Elements images = hostDoc.select("img[src$=-day.png]");

                        for (Element image : images) {
                            String pluginName = image.attr("src").substring(image.attr("src").lastIndexOf('/') + 1, image.attr("src").lastIndexOf('-'));
                            pluginName = pluginName.replaceAll(",", "");

                            log("            Found plugin " + pluginName);

                            // Add it to plugins list
                            boolean exists = false;
                            for (String s : plugins) {
                                if (s.equals(pluginName))
                                    exists = true;
                            }
                            if (exists)
                                log("                (already found)");
                            else
                                plugins.add(pluginName);
                        }
                    }
                }

                String formattedPluginsList = "public static String[] muninPlugins = {";

                for (String plugin : plugins)
                    formattedPluginsList += "\r\n\"" + plugin + "\",";

                // Remove trailing ","
                formattedPluginsList = formattedPluginsList.substring(0,  formattedPluginsList.length()-1);
                formattedPluginsList += "};";

                log(formattedPluginsList);

                break;
            case FETCH_DOCUMENTATION:
                recycle();

                // Configure compressor
                compressor.setEnabled(COMPRESS_HTML_FILES);
                compressor.setRemoveMultiSpaces(true);
                compressor.setRemoveIntertagSpaces(true);
                compressor.setRemoveQuotes(true);
                compressor.setSimpleDoctype(true);
                compressor.setRemoveStyleAttributes(true);
                compressor.setRemoveSurroundingSpaces("br,p");
                compressor.setPreserveLineBreaks(false);

                index = new Index();
                List<String> pluginsList = new ArrayList<String>();

                // Find core plugins documentation
                pluginsList.addAll(parseDoc(ParseType.CORE));

                // Find third part plugins documentation
                if (INCLUDE_THIRD_PARTY_PLUGINS)
                    pluginsList.addAll(parseDoc(ParseType.TIRDP));

                log("");

                Gson converter = new Gson();
                log(converter.toJson(index));

                compare();

                break;
        }
    }

    public static List<String> parseDoc(ParseType parseType) {
        log("Beginning " + parseType.name() + " discovery");
        List<String> pluginsList = new ArrayList<String>();

        int nbFails = 0;
        int nbSkipped_noDoc = 0;
        int nbOk = 0;
        int nbFound = 0;
        int total = 0;

        Document doc = null;
        try {
            doc = Jsoup.connect(
                    (parseType == ParseType.CORE) ? CORE_PLUGINS_HOME : THIRDP_PLUGINS_HOME
            ).get();
        } catch (Exception ex) { ex.printStackTrace(); }

        if (doc == null) {
            log("Failed downloading content.");
            return null;
        }

        // Find categories names
        Elements categories = doc.select("#nav ul li a");
        for (Element category : categories) {
            String categoryName = category.text();

            if (!categoryName.equals("Core") && !categoryName.equals("3rd-Party")) {
                index.addCategory(categoryName);
                total++;

                String targetLink = category.attr("abs:href");

                Document categoryDocument = null;
                try {
                    categoryDocument = Jsoup.connect(targetLink).get();
                } catch (Exception ex) { ex.printStackTrace(); }

                if (categoryDocument == null) {
                    nbFails++;
                    continue;
                }

                // Get nodes
                Elements nodes = categoryDocument.select("ul.groupview span.domain");
                for (Element node : nodes) {
                    String nodeName = node.text();
                    if (nodeName.equals("node.d"))
                        nodeName = "node";
                    else if (nodeName.contains("node.d."))
                        nodeName = nodeName.substring("node.d.".length());

                    index.addNode(nodeName);

                    // Find plugins links in page
                    Elements plugins = node.parent().select("span.host span.host a");
                    for (Element plugin : plugins) {
                        String pluginName = plugin.text();
                        // Get documentation
                        Document pluginDocument = null;
                        try {
                            pluginDocument = Jsoup.connect(plugin.attr("abs:href")).get();
                        } catch (Exception ex) { ex.printStackTrace(); }

                        if (pluginDocument == null) {
                            log("Failed downloading document for plugin " + pluginName);
                            nbFails++;
                            continue;
                        }

                        doFilters(pluginDocument);

                        String documentContent = pluginDocument.html();

                        if (documentContent.contains("<p>This plugin has no perldoc section yet.</p>")) {
                            //log("    Skipping " + pluginName + " (no doc)");
                            nbSkipped_noDoc++;
                            continue;
                        }

                        if (tryFind(pluginName)) {
                            log("Found known plugin " + pluginName);
                            nbFound++;
                        }
                        else
                            log("Found unknown plugin " + pluginName);

                        String fileName = parseType.name().toLowerCase() + "_" + categoryName + "_" + nodeName + "_" + pluginName + ".html";
                        fileName = fileName.replaceAll("/", "_");

                        index.addPlugin(pluginName, fileName);

                        documentContent = getBody(pluginDocument);

                        boolean replaceIfExists = parseType == ParseType.CORE;
                        if (writeFile(fileName, documentContent, replaceIfExists)) {
                            nbOk++;
                            pluginsList.add(pluginName);
                        }
                        else
                            log("          (category is " + categoryName + ")");
                    }
                }
            }
        }

        log("");
        log("FINISHED " + parseType.name() + ".");
        log(" * " + nbFails + " critical fails");
        log(" * " + nbSkipped_noDoc + " skipped (no doc)");
        log(" * " + nbOk + " OK");
        log(" * " + nbFound + " found / " + MuninPlugins.muninPlugins.length);
        log(total + " total.");
        log("");

        return pluginsList;
    }

    public static void log(String str) { System.out.println(str); }

    private static boolean writeFile(String fileName, String content, boolean replaceIfExists) {
        try {
            File file = new File(FILES_DIRECTORY + fileName);

            if (file.exists()) {
                if (replaceIfExists)
                    log("        Replacing file " + fileName);
                else {
                    log("        Avoiding file " + fileName + " overwriting");
                    return false;
                }
            }

            // Compress HTML
            content = compressor.compress(content);

            // if file doesnt exists, then create it
            if (!file.exists())
                file.createNewFile();

            FileWriter fw = new FileWriter(file.getAbsoluteFile());
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(content);
            bw.close();
            return true;
        } catch (IOException ex) {
            log("Failed file " + FILES_DIRECTORY + fileName);
            ex.printStackTrace();
            return false;
        }
    }

    /**
     * Apply filters on the document
     * @param doc
     */
    private static void doFilters(Document doc) {
        removeComments(doc);
        removeImages(doc);
        removeLinks(doc);
        addSourceItem(doc);
    }

    private static void removeComments(Node node) {
        for (int i = 0; i < node.childNodes().size();) {
            Node child = node.childNode(i);
            if (child.nodeName().equals("#comment"))
                child.remove();
            else {
                removeComments(child);
                i++;
            }
        }
    }
    private static void removeImages(Document document) {
        document.getElementsByTag("img").remove();
    }
    private static void removeLinks(Document document) {
        document.getElementsByTag("a").unwrap();
    }
    private static String getBody(Document document) {
        return document.getElementsByTag("body").get(0).html();
    }
    private static void addSourceItem(Document document) {
        Element e = document.createElement("div");
        e.text("Source : http://gallery.munin-monitoring.org/");
        document.getElementsByTag("body").get(0).appendChild(e);
    }

    private static boolean tryFind(String pluginName) {
        for (String str : MuninPlugins.muninPlugins) {
            if (str.equals(pluginName))
                return true;
        }
        return false;
    }

    /**
     * Removes all files inside output2/
     * Moves output/ files to output2/
     */
    private static void recycle() {
        File out = new File(FILES_DIRECTORY);
        File out2 = new File(FILES_DIRECTORY_old);

        // Empty out2
        for (File file : out2.listFiles())
            file.delete();

        // Copy out files to out2
        try {
            FileUtils.copyDirectory(out, out2);
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        // Empty out
        for (File file : out.listFiles())
            file.delete();
    }

    /**
     * Compares old and new output/ directories to see if there were any changes
     */
    private static void compare() {
        log("== Launching " + FILES_DIRECTORY + " and " + FILES_DIRECTORY_old + " directories comparison...");

        File out = new File(FILES_DIRECTORY);
        File out2 = new File(FILES_DIRECTORY_old);

        // Iterate over out in comparison to out2, then out2 in comparison to out
        for (File file : out.listFiles()) {
            // Search that file in out2
            boolean found = false;

            for (File searching : out2.listFiles()) {
                if (file.getName().equals(searching.getName()))
                    found = true;
            }

            if (!found)
                log("  + " + file.getName());
        }

        for (File file : out2.listFiles()) {
            boolean found = false;

            for (File searching : out.listFiles()) {
                if (file.getName().equals(searching.getName()))
                    found = true;
            }

            if (!found)
                log("  - " + file.getName());
        }

        // Compare files content
        for (File file : out.listFiles()) {
            // Find out2 file equivalent
            File file2 = null;

            for (File fileOut2 : out2.listFiles()) {
                if (file.getName().equals(fileOut2.getName()))
                    file2 = fileOut2;
            }

            if (file2 != null) {
                // Compare content
                try {
                    if (!file.isDirectory() && !file2.isDirectory() && !FileUtils.contentEquals(file, file2))
                        log("  != " + file.getName());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        log("Finished!");
    }
}
