/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2007-2015 Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.broad.igv.session;

import org.broad.igv.Globals;
import org.broad.igv.bedpe.InteractionTrack;
import org.broad.igv.data.CombinedDataSource;
import org.broad.igv.feature.Locus;
import org.broad.igv.feature.RegionOfInterest;
import org.broad.igv.feature.basepair.BasePairTrack;
import org.broad.igv.feature.dsi.DSITrack;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeListItem;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.feature.sprite.ClusterTrack;
import org.broad.igv.lists.GeneList;
import org.broad.igv.lists.GeneListManager;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.maf.MultipleAlignmentTrack;
import org.broad.igv.renderer.ColorScale;
import org.broad.igv.renderer.ColorScaleFactory;
import org.broad.igv.renderer.ContinuousColorScale;
import org.broad.igv.renderer.DataRange;
import org.broad.igv.sam.CoverageTrack;
import org.broad.igv.sam.EWigTrack;
import org.broad.igv.sam.SpliceJunctionTrack;
import org.broad.igv.track.*;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.TrackFilter;
import org.broad.igv.ui.TrackFilterElement;
import org.broad.igv.ui.color.ColorUtilities;
import org.broad.igv.ui.commandbar.GenomeListManager;
import org.broad.igv.ui.panel.FrameManager;
import org.broad.igv.ui.panel.ReferenceFrame;
import org.broad.igv.ui.panel.TrackPanel;
import org.broad.igv.ui.panel.TrackPanelScrollPane;
import org.broad.igv.ui.util.MessageUtils;
import org.broad.igv.util.*;
import org.broad.igv.util.FilterElement.BooleanOperator;
import org.broad.igv.util.FilterElement.Operator;
import org.broad.igv.util.collections.CollUtils;
import org.broad.igv.variant.VariantTrack;
import org.w3c.dom.*;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Class to parse an IGV session file
 */
public class IGVSessionReader implements SessionReader {

    private static Logger log = LogManager.getLogger(IGVSessionReader.class);
    private static String INPUT_FILE_KEY = "INPUT_FILE_KEY";
    private static Map<String, String> attributeSynonymMap = new HashMap();
    private static WeakReference<IGVSessionReader> currentReader;

    Collection<ResourceLocator> dataFiles; //package-private for unit testing
    private Collection<ResourceLocator> missingDataFiles;
    private boolean panelElementPresent = false;    // Until proven otherwise
    private int version;
    private String genomePath;

    private IGV igv;

    /**
     * Map of track id -> track.  It is important to maintain the order in which tracks are added, thus
     * the use of LinkedHashMap. We add tracks here when loaded and remove them when attributes are specified.
     */
    private final Map<String, List<Track>> leftoverTrackDictionary = Collections.synchronizedMap(new LinkedHashMap());

    /**
     * List of combined data source tracks.  Processing of data sources has to be deferred until all tracks
     * are loaded
     */
    private final List<Pair<CombinedDataTrack, Element>> combinedDataSourceTracks = new ArrayList<>();

    /**
     * Map of id -> track, for second pass through when tracks reference each other
     */
    private final Map<String, List<Track>> allTracks = Collections.synchronizedMap(new LinkedHashMap<>());

    private final Set<String> erroredResources = Collections.synchronizedSet(new HashSet<>());

    public List<Track> getTracksById(String trackId) {
        List<Track> tracks = allTracks.get(trackId);
        if (tracks == null) {
            // ID is usually a full file path or URL.  See if we can find the track with just filename
            if (!FileUtils.isRemote(trackId)) {
                String fn = (new File(trackId)).getName();
                tracks = allTracks.get(fn);
            }
            if (tracks == null) {
                // If still no match search for legacy gene annotation specifier
                Genome genome = GenomeManager.getInstance().getCurrentGenome();
                String legacyGeneTrackID = genome.getId() + "_genes";
                if (trackId.equals(legacyGeneTrackID)) {
                    if (genome.getGeneTrack() != null) {
                        return Arrays.asList(genome.getGeneTrack());
                    } else if (genome.getAnnotationTracks() != null && genome.getAnnotationTracks().size() > 0) {
                        return genome.getAnnotationTracks().values().iterator().next();
                    }
                }
            }
        }
        return tracks;
    }


    /**
     * Map of full path -> relative path.
     */
    Map<String, String> fullToRelPathMap = new HashMap<String, String>();

    private Track geneTrack = null;
    private Track seqTrack = null;
    private boolean hasTrackElments;

    static {
        attributeSynonymMap.put("DATA FILE", "DATA SET");
        attributeSynonymMap.put("TRACK NAME", "NAME");
    }


    public IGVSessionReader(IGV igv) {
        this.igv = igv;
        currentReader = new WeakReference<IGVSessionReader>(this);
    }


    /**
     * @param inputStream
     * @param session
     * @param sessionPath @return
     * @throws RuntimeException
     */

    public void loadSession(InputStream inputStream, Session session, String sessionPath) {

        Document document;
        try {
            document = Utilities.createDOMDocumentFromXmlStream(inputStream);
        } catch (Exception e) {
            log.error("Load session error", e);
            throw new RuntimeException(e);
        }

        NodeList trackElements = document.getElementsByTagName("Track");
        hasTrackElments = trackElements.getLength() > 0;

        HashMap additionalInformation = new HashMap();
        additionalInformation.put(INPUT_FILE_KEY, sessionPath);

        //  Find the root node, either "session" or "global
        NodeList nodes = document.getElementsByTagName(SessionElement.SESSION);
        if (nodes == null || nodes.getLength() == 0) {
            nodes = document.getElementsByTagName(SessionElement.GLOBAL);
        }
        Node rootNode = nodes.item(0);

        // Recursively process all nodes, starting with the root
        processRootNode(session, rootNode, additionalInformation, sessionPath);

        processCombinedDataSourceTracks();

        // Add tracks not explicitly allocated to panels.   This most often happens with hand created sessions lacking a panels section.
        addLeftoverTracks(leftoverTrackDictionary.values());


        if (session.getGroupTracksBy() != null && session.getGroupTracksBy().length() > 0) {
            igv.setGroupByAttribute(session.getGroupTracksBy());
        }

        if (session.isRemoveEmptyPanels()) {
            igv.getMainPanel().removeEmptyDataPanels();
        }

        igv.resetOverlayTracks();

    }

    /**
     * The main entry point
     *
     * @param session
     * @param node
     * @param additionalInformation
     * @param rootPath
     */

    private void processRootNode(Session session, Node node, HashMap additionalInformation, String rootPath) {

        if ((node == null) || (session == null)) {
            MessageUtils.showMessage("Invalid session file: root node not found");
            return;
        }

        String nodeName = node.getNodeName();
        if (!(nodeName.equalsIgnoreCase(SessionElement.GLOBAL) || nodeName.equalsIgnoreCase(SessionElement.SESSION))) {
            MessageUtils.showMessage("Session files must begin with a \"Global\" or \"Session\" element.  Found: " + nodeName);
            return;
        }

        Element element = (Element) node;
        process(session, node, additionalInformation, rootPath);

        String versionString = getAttribute(element, SessionAttribute.VERSION);
        try {
            version = Integer.parseInt(versionString);
        } catch (NumberFormatException e) {
            log.error("Non integer version number in session file: " + versionString);
        }

        // Load the genome, which can be an ID, or a path or URL to a .genome or indexed fasta file.
        String genomeId = getAttribute(element, SessionAttribute.GENOME);

        if (genomeId != null && genomeId.length() > 0) {
            if (genomeId.equals(GenomeManager.getInstance().getGenomeId())) {
                igv.resetSession(rootPath);
                GenomeManager.getInstance().restoreGenomeTracks(GenomeManager.getInstance().getCurrentGenome());
            } else {
                try {
                    GenomeListItem item = GenomeListManager.getInstance().getGenomeListItem(genomeId);
                    if (item != null) {
                        genomePath = item.getPath();
                        GenomeManager.getInstance().loadGenome(item.getPath(), null);
                    } else {
                        genomePath = genomeId;
                        if (!ParsingUtils.fileExists(genomePath)) {
                            genomePath = getAbsolutePath(genomeId, rootPath);
                        }
                        GenomeManager.getInstance().loadGenome(genomePath, null);
                    }
                } catch (IOException e) {
                    MessageUtils.showErrorMessage("Error loading genome: " + genomeId, e);
                    log.error("Error loading genome: " + genomeId, e);
                }
            }
        }

        session.setLocus(getAttribute(element, SessionAttribute.LOCUS));
        session.setGroupTracksBy(getAttribute(element, SessionAttribute.GROUP_TRACKS_BY));

        String nextAutoscaleGroup = getAttribute(element, SessionAttribute.NEXT_AUTOSCALE_GROUP);
        if (nextAutoscaleGroup != null) {
            try {
                session.setNextAutoscaleGroup(Integer.parseInt(nextAutoscaleGroup));
            } catch (NumberFormatException e) {
                log.error("Error setting next autoscale group", e);
            }
        }

        String removeEmptyTracks = getAttribute(element, "removeEmptyTracks");
        if (removeEmptyTracks != null) {
            try {
                Boolean b = Boolean.parseBoolean(removeEmptyTracks);
                session.setRemoveEmptyPanels(b);
            } catch (Exception e) {
                log.error("Error parsing removeEmptyTracks string: " + removeEmptyTracks, e);
            }
        }

        session.setVersion(version);
        NodeList elements = element.getChildNodes();
        process(session, elements, additionalInformation, rootPath);

        // ReferenceFrame.getInstance().invalidateLocationScale();
    }

    //TODO Check to make sure tracks are not being created twice
    //TODO -- DONT DO THIS FOR NEW SESSIONS
    private void addLeftoverTracks(Collection<List<Track>> tmp) {
        Map<String, TrackPanel> trackPanelCache = new HashMap();
        if (version < 3 || !panelElementPresent) {
            log.debug("Adding \"leftover\" tracks");

            //For resetting track panels later
            List<Map<TrackPanelScrollPane, Integer>> trackPanelAttrs = null;
            if (IGV.hasInstance()) {
                trackPanelAttrs = IGV.getInstance().getTrackPanelAttrs();
            }

            for (List<Track> tracks : tmp) {
                for (Track track : tracks) {
                    if (track == geneTrack) {
                        igv.setGenomeTracks(track);
                    } else if (track.getResourceLocator() != null) {
                        TrackPanel panel = trackPanelCache.get(track.getResourceLocator().getPath());
                        if (panel == null) {
                            panel = IGV.getInstance().getPanelFor(track);
                            trackPanelCache.put(track.getResourceLocator().getPath(), panel);
                        }
                        panel.addTrack(track);
                    }
                }
            }

            if (IGV.hasInstance()) {
                IGV.getInstance().resetPanelHeights(trackPanelAttrs.get(0), trackPanelAttrs.get(1));
            }
        }
    }


    /**
     * Process a single session element node.
     *
     * @param session
     * @param element
     */
    private void process(Session session, Node element, HashMap additionalInformation, String rootPath) {

        if ((element == null) || (session == null)) {
            return;
        }

        String nodeName = element.getNodeName();
        if (nodeName.equalsIgnoreCase(SessionElement.RESOURCES) ||
                nodeName.equalsIgnoreCase(SessionElement.FILES)) {
            processResources(session, (Element) element, additionalInformation, rootPath);
        } else if (nodeName.equalsIgnoreCase(SessionElement.RESOURCE) ||
                nodeName.equalsIgnoreCase(SessionElement.DATA_FILE)) {
            processResource(session, (Element) element, additionalInformation, rootPath);
        } else if (nodeName.equalsIgnoreCase(SessionElement.REGIONS)) {
            processRegions(session, (Element) element, additionalInformation, rootPath);
        } else if (nodeName.equalsIgnoreCase(SessionElement.REGION)) {
            processRegion(session, (Element) element, additionalInformation, rootPath);
        } else if (nodeName.equalsIgnoreCase(SessionElement.GENE_LIST)) {
            processGeneList(session, (Element) element, additionalInformation);
        } else if (nodeName.equalsIgnoreCase(SessionElement.FILTER)) {
            processFilter(session, (Element) element, additionalInformation, rootPath);
        } else if (nodeName.equalsIgnoreCase(SessionElement.FILTER_ELEMENT)) {
            processFilterElement(session, (Element) element, additionalInformation, rootPath);
        } else if (nodeName.equalsIgnoreCase(SessionElement.COLOR_SCALES)) {
            processColorScales(session, (Element) element, additionalInformation, rootPath);
        } else if (nodeName.equalsIgnoreCase(SessionElement.COLOR_SCALE)) {
            processColorScale(session, (Element) element, additionalInformation, rootPath);
        } else if (nodeName.equalsIgnoreCase(SessionElement.PREFERENCES)) {
            processPreferences(session, (Element) element, additionalInformation);
        } else if (nodeName.equalsIgnoreCase(SessionElement.DATA_TRACKS) ||
                nodeName.equalsIgnoreCase(SessionElement.FEATURE_TRACKS) ||
                nodeName.equalsIgnoreCase(SessionElement.PANEL)) {
            processPanel(session, (Element) element, additionalInformation, rootPath);
        } else if (nodeName.equalsIgnoreCase(SessionElement.PANEL_LAYOUT)) {
            processPanelLayout(session, (Element) element, additionalInformation);
        } else if (nodeName.equalsIgnoreCase(SessionElement.HIDDEN_ATTRIBUTES)) {
            processHiddenAttributes(session, (Element) element, additionalInformation);
        } else if (nodeName.equalsIgnoreCase(SessionElement.VISIBLE_ATTRIBUTES)) {
            processVisibleAttributes(session, (Element) element, additionalInformation);
        }

    }

    private void processResources(Session session, Element element, HashMap additionalInformation, String rootPath) {

        dataFiles = new ArrayList();
        missingDataFiles = new ArrayList();
        NodeList elements = element.getChildNodes();

        process(session, elements, additionalInformation, rootPath);

        if (missingDataFiles.size() > 0) {
            StringBuffer message = new StringBuffer();
            message.append("<html>The following data file(s) could not be located.<ul>");
            for (ResourceLocator file : missingDataFiles) {
                if (file.getDBUrl() == null) {
                    message.append("<li>");
                    message.append(file.getPath());
                    message.append("</li>");
                } else {
                    message.append("<li>Server: ");
                    message.append(file.getDBUrl());
                    message.append("  Path: ");
                    message.append(file.getPath());
                    message.append("</li>");
                }
            }
            message.append("</ul>");
            message.append("Common reasons for this include: ");
            message.append("<ul><li>The session or data files have been moved.</li> ");
            message.append("<li>The data files are located on a drive that is not currently accessible.</li></ul>");
            message.append("</html>");

            MessageUtils.showMessage(message.toString());
        }

        // Filter data files that are included in genome annotations.  This is to work around a bug introduced with
        // json genomes where the genome annotation resources were (doubly) included as session
        List<ResourceLocator> genomeResources = GenomeManager.getInstance().getCurrentGenome().getAnnotationResources();
        Set<String> absoluteGenomeAnnotationPaths = genomeResources == null ? Collections.emptySet() :
                genomeResources.stream().map(rl -> rl.getPath()).collect(Collectors.toSet());

        if (dataFiles.size() > 0) {

            final List<String> errors = new ArrayList<String>();

            // Load files concurrently -- TODO, put a limit on # of threads?
            List<Thread> threads = new ArrayList(dataFiles.size());
            long t0 = System.currentTimeMillis();

            List<Runnable> synchronousLoads = new ArrayList<Runnable>();

            for (final ResourceLocator locator : dataFiles) {

                if (absoluteGenomeAnnotationPaths.contains(FileUtils.getAbsolutePath(locator.getPath(), genomePath))) {
                    continue;
                }

                Runnable runnable = () -> {
                    try {
                        List<Track> tracks = igv.load(locator);
                        for (Track track : tracks) {

                            if (track == null) {
                                log.info("Null track for resource " + locator.getPath());
                                continue;
                            }

                            String id = track.getId();
                            if (id == null) {
                                log.info("Null track id for resource " + locator.getPath());
                                continue;
                            }
                            // id is often an absolute file path.  Use just the filename if unique
                            if (!FileUtils.isRemote(id)) {
                                String fn = (new File(id)).getName();
                                if (!allTracks.containsKey(fn)) {
                                    id = fn;
                                }
                            }

                            List<Track> trackList = leftoverTrackDictionary.get(id);
                            if (trackList == null) {
                                trackList = new ArrayList();
                                leftoverTrackDictionary.put(id, trackList);
                                allTracks.put(id, trackList);
                            }
                            trackList.add(track);
                        }
                    } catch (Exception e) {
                        erroredResources.add(locator.getPath());
                        log.error("Error loading resource " + locator.getPath(), e);
                        String ms = "<b>" + locator.getPath() + "</b><br>&nbsp;&nbsp;" + e.toString() + "<br>";
                        errors.add(ms);
                    }
                };

                // Run synchronously if in batch mode or if there are no "track"  elements
                if (Globals.isBatch() || !hasTrackElments) {
                    synchronousLoads.add(runnable);
                } else {
                    Thread t = new Thread(runnable);
                    threads.add(t);
                    t.start();
                }
            }
            // Wait for all threads to complete
            for (Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException ignore) {
                    log.error(ignore);
                }
            }

            // Now load data that must be loaded synchronously
            for (Runnable runnable : synchronousLoads) {
                runnable.run();
            }

            long dt = System.currentTimeMillis() - t0;
            log.debug("Total load time = " + dt);

            if (errors.size() > 0) {
                StringBuffer buf = new StringBuffer();
                buf.append("<html>Errors were encountered loading the session:<br>");
                for (String msg : errors) {
                    buf.append(msg);
                }
                MessageUtils.showMessage(buf.toString());
            }

        }
        dataFiles = null;
    }

    /**
     * Load a single resource.
     * <p/>
     * Package private for unit testing
     *
     * @param session
     * @param element
     * @param additionalInformation
     */
    void processResource(Session session, Element element, HashMap additionalInformation, String rootPath) {

        String nodeName = element.getNodeName();
        boolean oldSession = nodeName.equals(SessionElement.DATA_FILE);

        String label = getAttribute(element, SessionAttribute.LABEL);
        String name = getAttribute(element, SessionAttribute.NAME);
        String sampleId = getAttribute(element, SessionAttribute.SAMPLE_ID);
        String description = getAttribute(element, SessionAttribute.DESCRIPTION);
        String type = getAttribute(element, SessionAttribute.TYPE);
        String coverage = getAttribute(element, SessionAttribute.COVERAGE);
        String mapping = getAttribute(element, SessionAttribute.MAPPING);
        String trackLine = getAttribute(element, SessionAttribute.TRACK_LINE);
        String colorString = getAttribute(element, SessionAttribute.COLOR);
        String index = getAttribute(element, SessionAttribute.INDEX);

        // URL to a database or webservice
        String serverURL = getAttribute(element, SessionAttribute.SERVER_URL);

        // Older sessions used the "name" attribute for the path.
        String path = getAttribute(element, SessionAttribute.PATH);

        if (oldSession && name != null) {
            path = name;
            int idx = name.lastIndexOf("/");
            if (idx > 0 && idx + 1 < name.length()) {
                name = name.substring(idx + 1);
            }
        }

        String absolutePath = (rootPath == null || "ga4gh".equals(type) || ParsingUtils.isDataURL(path)) ?
                path :
                getAbsolutePath(path, rootPath);

        fullToRelPathMap.put(absolutePath, path);

        ResourceLocator resourceLocator = new ResourceLocator(serverURL, absolutePath);

        if (index != null) resourceLocator.setIndexPath(index);

        if (coverage != null) {
            String absoluteCoveragePath = coverage.equals(".") ? coverage : getAbsolutePath(coverage, rootPath);
            resourceLocator.setCoverage(absoluteCoveragePath);
        }

        if (mapping != null) {
            String absoluteMappingPath = mapping.equals(".") ? mapping : getAbsolutePath(mapping, rootPath);
            resourceLocator.setMappingPath(absoluteMappingPath);
        }

        String url = getAttribute(element, SessionAttribute.URL);
        if (url == null) {
            url = getAttribute(element, SessionAttribute.FEATURE_URL);
        }
        resourceLocator.setFeatureInfoURL(url);

        String infolink = getAttribute(element, SessionAttribute.HYPERLINK);
        if (infolink == null) {
            infolink = getAttribute(element, SessionAttribute.INFOLINK);
        }
        resourceLocator.setTrackInfoURL(infolink);


        // Label is deprecated in favor of name.
        if (name != null) {
            resourceLocator.setName(name);
        } else {
            resourceLocator.setName(label);
        }

        resourceLocator.setSampleId(sampleId);


        resourceLocator.setDescription(description);
        // This test added to get around earlier bug in the writer
        if (type != null && !type.equals("local")) {
            resourceLocator.setFormat(type);
        }
        resourceLocator.setCoverage(coverage);
        resourceLocator.setTrackLine(trackLine);

        if (colorString != null) {
            try {
                Color c = ColorUtilities.stringToColor(colorString);
                resourceLocator.setColor(c);
            } catch (Exception e) {
                log.error("Error setting color: ", e);
            }
        }

        dataFiles.add(resourceLocator);

        NodeList elements = element.getChildNodes();

        process(session, elements, additionalInformation, rootPath);

    }

    private void processRegions(Session session, Element element, HashMap additionalInformation, String rootPath) {

        session.clearRegionsOfInterest();
        NodeList elements = element.getChildNodes();
        process(session, elements, additionalInformation, rootPath);
    }

    private void processRegion(Session session, Element element, HashMap additionalInformation, String rootPath) {

        String chromosome = getAttribute(element, SessionAttribute.CHROMOSOME);
        String start = getAttribute(element, SessionAttribute.START_INDEX);
        String end = getAttribute(element, SessionAttribute.END_INDEX);
        String description = getAttribute(element, SessionAttribute.DESCRIPTION);

        RegionOfInterest region = new RegionOfInterest(chromosome, new Integer(start), new Integer(end), description);
        IGV.getInstance().addRegionOfInterest(region);

        NodeList elements = element.getChildNodes();
        process(session, elements, additionalInformation, rootPath);
    }

    private void processHiddenAttributes(Session session, Element element, HashMap additionalInformation) {
        NodeList elements = element.getChildNodes();
        Set<String> attributes = new HashSet();
        for (int i = 0; i < elements.getLength(); i++) {
            Node childNode = elements.item(i);
            if (childNode.getNodeName().equals("Attribute")) {
                attributes.add(((Element) childNode).getAttribute(SessionAttribute.NAME));
            }
        }
        session.setHiddenAttributes(attributes);
    }


    /**
     * For backward compatibility
     *
     * @param session
     * @param element
     * @param additionalInformation
     */
    private void processVisibleAttributes(Session session, Element element, HashMap additionalInformation) {

        NodeList elements = element.getChildNodes();
        if (elements.getLength() > 0) {
            Set<String> visibleAttributes = new HashSet();
            for (int i = 0; i < elements.getLength(); i++) {
                Node childNode = elements.item(i);
                if (childNode.getNodeName().equals(SessionElement.VISIBLE_ATTRIBUTE)) {
                    visibleAttributes.add(((Element) childNode).getAttribute(SessionAttribute.NAME));
                }
            }

            final List<String> attributeNames = AttributeManager.getInstance().getAttributeNames();
            Set<String> hiddenAttributes = new HashSet<String>(attributeNames);
            hiddenAttributes.removeAll(visibleAttributes);
            session.setHiddenAttributes(hiddenAttributes);

        }
    }

    private void processGeneList(Session session, Element element, HashMap additionalInformation) {

        String name = getAttribute(element, SessionAttribute.NAME);

        String txt = element.getTextContent();
        String[] genes = txt.trim().split("\\s+");
        GeneList gl = new GeneList(name, Arrays.asList(genes));
        GeneListManager.getInstance().addGeneList(gl);
        session.setCurrentGeneList(gl);

        // Adjust frames
        processFrames(element);
    }

    private void processFrames(Element element) {
        NodeList elements = element.getChildNodes();
        if (elements.getLength() > 0) {
            Map<String, ReferenceFrame> frames = new HashMap();
            for (ReferenceFrame f : FrameManager.getFrames()) {
                frames.put(f.getName(), f);
            }
            List<ReferenceFrame> reorderedFrames = new ArrayList();

            for (int i = 0; i < elements.getLength(); i++) {
                Node childNode = elements.item(i);
                if (childNode.getNodeName().equalsIgnoreCase(SessionElement.FRAME)) {
                    String frameName = getAttribute((Element) childNode, SessionAttribute.NAME);

                    ReferenceFrame f = frames.get(frameName);
                    if (f != null) {
                        reorderedFrames.add(f);
                        try {
                            String chr = getAttribute((Element) childNode, SessionAttribute.CHR);
                            final String startString =
                                    getAttribute((Element) childNode, SessionAttribute.START).replace(",", "");
                            final String endString =
                                    getAttribute((Element) childNode, SessionAttribute.END).replace(",", "");
                            int start = ParsingUtils.parseInt(startString);
                            int end = ParsingUtils.parseInt(endString);
                            org.broad.igv.feature.Locus locus = new Locus(chr, start, end);
                            f.jumpTo(locus);
                        } catch (NumberFormatException e) {
                            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                        }
                    }

                }
            }
            if (reorderedFrames.size() > 0) {
                FrameManager.setFrames(reorderedFrames);
            }
        }
        IGV.getInstance().resetFrames();
    }

    private void processFilter(Session session, Element element, HashMap additionalInformation, String rootPath) {

        String match = getAttribute(element, SessionAttribute.FILTER_MATCH);
        String showAllTracks = getAttribute(element, SessionAttribute.FILTER_SHOW_ALL_TRACKS);

        String filterName = getAttribute(element, SessionAttribute.NAME);
        TrackFilter filter = new TrackFilter(filterName, null);
        additionalInformation.put(SessionElement.FILTER, filter);

        NodeList elements = element.getChildNodes();
        process(session, elements, additionalInformation, rootPath);

        // Save the filter
        session.setFilter(filter);

        // Set filter properties
        if ("all".equalsIgnoreCase(match)) {
            IGV.getInstance().setFilterMatchAll(true);
        } else if ("any".equalsIgnoreCase(match)) {
            IGV.getInstance().setFilterMatchAll(false);
        }

        if ("true".equalsIgnoreCase(showAllTracks)) {
            IGV.getInstance().setFilterShowAllTracks(true);
        } else {
            IGV.getInstance().setFilterShowAllTracks(false);
        }
    }

    private void processFilterElement(Session session, Element element,
                                      HashMap additionalInformation, String rootPath) {

        TrackFilter filter = (TrackFilter) additionalInformation.get(SessionElement.FILTER);
        String item = getAttribute(element, SessionAttribute.ITEM);
        String operator = getAttribute(element, SessionAttribute.OPERATOR);
        String value = getAttribute(element, SessionAttribute.VALUE);
        String booleanOperator = getAttribute(element, SessionAttribute.BOOLEAN_OPERATOR);

        Operator opEnum = CollUtils.findValueOf(Operator.class, operator);
        BooleanOperator boolEnum = BooleanOperator.valueOf(booleanOperator.toUpperCase());
        TrackFilterElement trackFilterElement = new TrackFilterElement(filter, item,
                opEnum, value, boolEnum);
        filter.add(trackFilterElement);

        NodeList elements = element.getChildNodes();
        process(session, elements, additionalInformation, rootPath);
    }

    /**
     * A counter to generate unique panel names.  Needed for backward-compatibility of old session files.
     */
    private int panelCounter = 1;

    /**
     * @param node
     * @return Whether this node is a track
     */
    private static boolean nodeIsTrack(Node node) {
        return node.getNodeName() != null &&
                (node.getNodeName().equalsIgnoreCase(SessionElement.DATA_TRACK) ||
                        node.getNodeName().equalsIgnoreCase(SessionElement.TRACK));
    }

    private void processPanel(Session session, Element element, HashMap additionalInformation, String rootPath) {

        if (panelElementPresent == false) {
            // First panel to be processed, do this only once.

            // Add any tracks loaded as a side effect of loading genome, these need to be removed and remembered
            final List<Track> tmp = igv.getAllTracks();
            for (Track track : tmp) {
                final String id = track.getId();
                final List<Track> trackList = Arrays.asList(track);
                leftoverTrackDictionary.put(id, trackList);
                this.allTracks.put(id, trackList);
            }

            // Tracks, if any,  will be placed back as prescribed by panel elements
            removeAllTracksFromPanels();
        }

        panelElementPresent = true;
        String panelName = element.getAttribute("name");
        if (panelName == null) {
            panelName = "Panel" + panelCounter++;
        }

        List<Track> panelTracks = new ArrayList();
        NodeList elements = element.getChildNodes();
        for (int i = 0; i < elements.getLength(); i++) {
            Node childNode = elements.item(i);
            if (nodeIsTrack(childNode)) {
                List<Track> tracks = processTrack(session, (Element) childNode, additionalInformation, rootPath);
                if (tracks != null) {
                    panelTracks.addAll(tracks);
                }
            } else {
                process(session, childNode, additionalInformation, rootPath);
            }
        }

        //We make a second pass through, resolving references to tracks which may have been processed after being referenced.
        //For instance if Track 2 referenced Track 4
        for (Track track : panelTracks) {
            if (track instanceof FeatureTrack) {
                FeatureTrack featureTrack = (FeatureTrack) track;
                featureTrack.updateTrackReferences(panelTracks);
            } else if (track instanceof DataSourceTrack) {
                DataSourceTrack dataTrack = (DataSourceTrack) track;
                dataTrack.updateTrackReferences(panelTracks);
            }
        }

        TrackPanel panel = IGV.getInstance().getTrackPanel(panelName);
        panel.addTracks(panelTracks);
    }

    private void processPanelLayout(Session session, Element element, HashMap additionalInformation) {

        String nodeName = element.getNodeName();

        NamedNodeMap tNodeMap = element.getAttributes();
        for (int i = 0; i < tNodeMap.getLength(); i++) {
            Node node = tNodeMap.item(i);
            String name = node.getNodeName();
            if (name.equals("dividerFractions")) {
                String value = node.getNodeValue();
                String[] tokens = value.split(",");
                double[] divs = new double[tokens.length];
                try {
                    for (int j = 0; j < tokens.length; j++) {
                        divs[j] = Double.parseDouble(tokens[j]);
                    }
                    session.setDividerFractions(divs);
                } catch (NumberFormatException e) {
                    log.error("Error parsing divider locations", e);
                }
            }
        }
    }


    /**
     * Process a track element.  This should return a single track, but could return multiple tracks since the
     * uniqueness of the track id is not enforced.
     *
     * @param session
     * @param element
     * @param additionalInformation
     * @return
     */

    private List<Track> processTrack(Session session, Element element, HashMap additionalInformation, String rootPath) {

        String id = getAttribute(element, SessionAttribute.ID);

        // Find track matching element id, created earlier from "Resource or File" elements, or during genome load.
        // Normally this is a single track, but that can't be assumed as uniqueness of "id" is not enforced.
        List<Track> matchedTracks = getTracksById(id);

        if (matchedTracks == null) {
            //Try creating an absolute path for the id
            if (id != null) {
                matchedTracks = allTracks.get(getAbsolutePath(id, rootPath));
            }
        }

        if (matchedTracks != null) {
            for (final Track track : matchedTracks) {
                track.unmarshalXML(element, version);
            }
            leftoverTrackDictionary.remove(id);
        } else {
            // No match found, element represents either (1) a track from a file that errored during load, for example
            // a file that has been deleted, or (2) a track not created from "Resource" or genome load.  These include
            // reference sequence, combined,  and merged tracks.

            // Check for errors during load
//            String absPath = getAbsolutePath(id, rootPath);
//            if (erroredResources.contains(id) || erroredResources.contains(absPath)) {
//                return Collections.emptyList();
//            }

//            String className = getAttribute(element, "clazz");
//            if (className != null &&
//                    (className.contains("MergedTracks") ||
//                            className.contains("CombinedDataTrack") ||
//                            className.contains("SequenceTrack") ||
//                            className.contains("BlatTrack"))) {
            String className = getAttribute(element, "clazz");
            try {
                Track track = createTrack(className, element);
                if (track != null) {

                    track.unmarshalXML(element, version);
                    matchedTracks = Arrays.asList(track);
                    allTracks.put(track.getId(), matchedTracks);   // Important for second pass

                    // Special tracks
                    if (className.contains("CombinedDataTrack")) {
                        combinedDataSourceTracks.add(new Pair(track, element));
                    }

                    if (className.contains("MergedTracks")) {
                        List<DataTrack> memberTracks = processChildTracks(session, element, additionalInformation, rootPath);
                        ((MergedTracks) track).setMemberTracks(memberTracks);

                        NodeList nodeList = element.getElementsByTagName("DataRange");
                        if (nodeList != null && nodeList.getLength() > 0) {
                            Element dataRangeElement = (Element) nodeList.item(0);
                            try {
                                DataRange dataRange = new DataRange(dataRangeElement, version);
                                track.setDataRange(dataRange);
                            } catch (Exception e) {
                                log.error("Unrecognized DataRange");
                            }
                        }
                    }
                } else {
                    log.warn("Warning.  No tracks were found with id: " + id + " in session file");
                }
            } catch (Exception e) {
                log.error("Error restoring track: " + element.toString(), e);
                MessageUtils.showMessage("Error loading track: " + element.toString());
            }
        }
        //}

        NodeList elements = element.getChildNodes();
        process(session, elements, additionalInformation, rootPath);

        return matchedTracks;
    }

    /**
     * Recursively loop through children of {@code element}, and process them as tracks iff they are determined
     * to be so
     *
     * @param session
     * @param element
     * @param additionalInformation
     * @param rootPath
     * @return List of processed tracks.
     */
    private List<DataTrack> processChildTracks(Session session, Element element, HashMap additionalInformation, String rootPath) {

        NodeList memberTrackNodes = element.getChildNodes();
        List<DataTrack> memberTracks = new ArrayList<>(memberTrackNodes.getLength());
        for (int index = 0; index < memberTrackNodes.getLength(); index++) {
            Node memberNode = memberTrackNodes.item(index);
            if (nodeIsTrack(memberNode)) {
                List<Track> addedTracks = processTrack(session, (Element) memberNode, additionalInformation, rootPath);
                if (addedTracks != null) {
                    for (Track t : addedTracks) {
                        if (t instanceof DataTrack) {
                            memberTracks.add((DataTrack) t);
                        } else {
                            log.error("Unexpected MergedTrack member class: " + t.getClass().getName());
                        }
                    }
                }
            }
        }
        return memberTracks;
    }

    /**
     * Process combined data tracks, these are tracks composed from dependent tracks by simple arithmetic operations.
     */
    private void processCombinedDataSourceTracks() {

        Map<CombinedDataTrack, CombinedDataSource> sourceMap = new HashMap<>();

        // First pass -- create data sources
        for (Pair<CombinedDataTrack, Element> pair : this.combinedDataSourceTracks) {

            Element element = pair.getSecond();
            CombinedDataTrack combinedTrack = pair.getFirst();

            DataTrack track1 = null;
            DataTrack track2 = null;
            List<Track> tmp = getTracksById(element.getAttribute("track1"));
            if (tmp != null && tmp.size() > 0) {
                track1 = (DataTrack) tmp.get(0);
            }
            tmp = getTracksById(element.getAttribute("track2"));
            if (tmp != null && tmp.size() > 0) {
                track2 = (DataTrack) tmp.get(0);
            }

            if (track1 == null || track2 == null) {
                log.error("Missing track for combined track: " + pair.getFirst().getName());
                return;
            }
            CombinedDataSource.Operation op = CombinedDataSource.Operation.valueOf(element.getAttribute("op"));

            CombinedDataSource source = new CombinedDataSource(track1, track2, op);
            sourceMap.put(combinedTrack, source);
        }

        // Now set datasource on tracks.  This needs to be deferred as combined data sources can reference other
        // combined sources, we need to instantiate the entire tree before using a datasource
        for (Map.Entry<CombinedDataTrack, CombinedDataSource> entry : sourceMap.entrySet()) {
            entry.getKey().setDatasource(entry.getValue());
        }

    }

    private void processColorScales(Session session, Element element, HashMap additionalInformation, String rootPath) {

        NodeList elements = element.getChildNodes();
        process(session, elements, additionalInformation, rootPath);
    }

    private void processColorScale(Session session, Element element, HashMap additionalInformation, String rootPath) {

        String trackType = getAttribute(element, SessionAttribute.TYPE);
        String value = getAttribute(element, SessionAttribute.VALUE);

        setColorScaleSet(session, trackType, value);

        NodeList elements = element.getChildNodes();
        process(session, elements, additionalInformation, rootPath);
    }

    private void processPreferences(Session session, Element element, HashMap additionalInformation) {

        NodeList elements = element.getChildNodes();
        for (int i = 0; i < elements.getLength(); i++) {
            Node child = elements.item(i);
            if (child.getNodeName().equalsIgnoreCase(SessionElement.PROPERTY)) {
                Element childNode = (Element) child;
                String name = getAttribute(childNode, SessionAttribute.NAME);
                String value = getAttribute(childNode, SessionAttribute.VALUE);
                session.setPreference(name, value);
            }
        }
    }


    /**
     * Process a list of session element nodes.
     *
     * @param session
     * @param elements
     */
    private void process(Session session, NodeList elements, HashMap additionalInformation, String rootPath) {
        for (int i = 0; i < elements.getLength(); i++) {
            Node childNode = elements.item(i);
            process(session, childNode, additionalInformation, rootPath);
        }
    }

    public void setColorScaleSet(Session session, String type, String value) {

        if (type == null | value == null) {
            return;
        }

        TrackType trackType = CollUtils.valueOf(TrackType.class, type.toUpperCase(), TrackType.OTHER);

        // TODO -- refactor to remove instanceof / cast.  Currently only ContinuousColorScale is handled
        ColorScale colorScale = ColorScaleFactory.getScaleFromString(value);
        if (colorScale instanceof ContinuousColorScale) {
            session.setColorScale(trackType, (ContinuousColorScale) colorScale);
        }

        // ColorScaleFactory.setColorScale(trackType, colorScale);
    }

    private static String getAttribute(Element element, String key) {
        String value = element.getAttribute(key);
        if (value != null) {
            if (value.trim().equals("")) {
                value = null;
            }
        }
        return value;
    }


    /**
     * Uses {@link #currentReader} to lookup matching tracks by id, or
     * searches allTracks if sessionReader is null
     *
     * @param trackId
     * @param allTracks
     * @return
     */
    public static Track getMatchingTrack(String trackId, List<Track> allTracks) {
        IGVSessionReader reader = currentReader.get();
        List<Track> matchingTracks;
        if (reader != null) {
            matchingTracks = reader.getTracksById(trackId);
        } else {
            if (allTracks == null)
                throw new IllegalStateException("No session reader and no tracks to search to resolve Track references");
            matchingTracks = new ArrayList<>();
            for (Track track : allTracks) {
                if (trackId.equals(track.getId())) {
                    matchingTracks.add(track);
                    break;
                }
            }
        }
        if (matchingTracks == null || matchingTracks.size() == 0) {
            //Either the session file is corrupted, or we just haven't loaded the relevant track yet
            return null;
        } else if (matchingTracks.size() >= 2) {
            log.debug("Found multiple tracks with id  " + trackId + ", using the first");
        }
        return matchingTracks.get(0);
    }

    /**
     * Create a track object for the given class name.  In the past this was done by reflection.   This was a bad
     * idea.  Among other issues the full class name gets hardcoded into sessions preventing future refactoring
     * or renaming.  Thus the lookup table approach below, with reflection at the end in case we miss any.
     *
     * @param className
     * @return
     * @throws ClassNotFoundException
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws java.lang.reflect.InvocationTargetException
     * @throws NoSuchMethodException
     */
    private Track createTrack(String className, Element element) throws ClassNotFoundException, InstantiationException, IllegalAccessException, java.lang.reflect.InvocationTargetException, NoSuchMethodException {

        if (className.contains("BasePairTrack")) {
            return new BasePairTrack();
        } else if (className.contains("BlatTrack")) {
            return new BlatTrack();
        } else if (className.contains("ClusterTrack")) {
            return new ClusterTrack();
        } else if (className.contains("CNFreqTrack")) {
            return new CNFreqTrack();
        } else if (className.contains("CoverageTrack")) {
            return new CoverageTrack();
        } else if (className.contains("DataSourceTrack")) {
            return new DataSourceTrack();
        } else if (className.contains("DSITrack")) {
            return new DSITrack();
        } else if (className.contains("EWigTrack")) {
            return new EWigTrack();
        } else if (className.contains("FeatureTrack")) {
            return new FeatureTrack();
        } else if (className.contains("MotifTrack")) {
            return new MotifTrack();
        } else if (className.contains("GisticTrack")) {
            return new GisticTrack();
        } else if (className.contains("InteractionTrack")) {
            return new InteractionTrack();
        } else if (className.contains("MergedTracks")) {
            return new MergedTracks();
        } else if (className.contains("CombinedDataTrack")) {
            String id = element.getAttribute("id");
            String name = element.getAttribute("name");
            return new CombinedDataTrack(id, name);
        } else if (className.contains("MultipleAlignmentTrack")) {
            return new MultipleAlignmentTrack();
        } else if (className.contains("MutationTrack")) {
            return new MutationTrack();
        } else if (className.contains("SelectableFeatureTrack")) {
            return new SelectableFeatureTrack();
        } else if (className.contains("SpliceJunctionTrack")) {
            return new SpliceJunctionTrack();
        } else if (className.contains("VariantTrack")) {
            return new VariantTrack();
        } else if (className.contains("SequenceTrack")) {
            return new SequenceTrack("Reference sequence");
        } else {
            log.warn("Unrecognized class name: " + className);
            try {
                Class clazz = SessionElement.getClass(className);
                return (Track) clazz.getConstructor().newInstance();
            } catch (Exception e) {
                log.error("Error attempting Track creation ", e);
                return null;
            }
        }
    }

    private void removeAllTracksFromPanels() {
        List<TrackPanel> panels = igv.getTrackPanels();
        for (TrackPanel trackPanel : panels) {
            trackPanel.removeAllTracks();
        }
    }

    private String getAbsolutePath(String path, String rootPath) {
        String absolute = FileUtils.getAbsolutePath(path, rootPath);
        if (!(new File(absolute)).exists() && path.startsWith("~")) {
            String tmp = FileUtils.getAbsolutePath(path.replaceFirst("~", System.getProperty("user.home")), rootPath);
            if ((new File(tmp)).exists()) {
                absolute = tmp;
            }
        }
        return absolute;
    }

}
