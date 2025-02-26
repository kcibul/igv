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

package org.broad.igv.sam;


import org.broad.igv.Globals;
import org.broad.igv.event.AlignmentTrackEvent;
import org.broad.igv.event.IGVEventBus;
import org.broad.igv.event.IGVEventObserver;
import org.broad.igv.feature.FeatureUtils;
import org.broad.igv.feature.Range;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.jbrowse.CircularViewUtilities;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.prefs.Constants;
import org.broad.igv.prefs.IGVPreferences;
import org.broad.igv.prefs.PreferencesManager;
import org.broad.igv.renderer.GraphicUtils;
import org.broad.igv.session.Persistable;
import org.broad.igv.track.*;
import org.broad.igv.ui.FontManager;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.color.ColorTable;
import org.broad.igv.ui.color.ColorUtilities;
import org.broad.igv.ui.color.PaletteColorTable;
import org.broad.igv.ui.panel.FrameManager;
import org.broad.igv.ui.panel.IGVPopupMenu;
import org.broad.igv.ui.panel.ReferenceFrame;
import org.broad.igv.ui.util.MessageUtils;
import org.broad.igv.util.ResourceLocator;
import org.broad.igv.util.StringUtils;
import org.broad.igv.util.blat.BlatClient;
import org.broad.igv.util.collections.CollUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.*;

import static org.broad.igv.prefs.Constants.*;

/**
 * @author jrobinso
 */

public class AlignmentTrack extends AbstractTrack implements IGVEventObserver {

    private static Logger log = LogManager.getLogger(AlignmentTrack.class);

    // Alignment colors
    static final Color DEFAULT_ALIGNMENT_COLOR = new Color(185, 185, 185); //200, 200, 200);

    public enum ColorOption {
        INSERT_SIZE,
        READ_STRAND,
        FIRST_OF_PAIR_STRAND,
        PAIR_ORIENTATION,
        READ_ORDER,
        SAMPLE,
        READ_GROUP,
        LIBRARY,
        MOVIE,
        ZMW,
        BISULFITE,
        NOMESEQ,
        TAG,
        NONE,
        UNEXPECTED_PAIR,
        MAPPED_SIZE,
        LINK_STRAND,
        YC_TAG,
        BASE_MODIFICATION,
        BASE_MODIFICATION_5MC,
        BASE_MODIFICATION_C,
        SMRT_SUBREAD_IPD,
        SMRT_SUBREAD_PW,
        SMRT_CCS_FWD_IPD,
        SMRT_CCS_FWD_PW,
        SMRT_CCS_REV_IPD,
        SMRT_CCS_REV_PW;

        public boolean isBaseMod() {
            return this == BASE_MODIFICATION || this == BASE_MODIFICATION_5MC || this == BASE_MODIFICATION_C;
        }

        public boolean isSMRTKinetics() {
            switch (this) {
                case SMRT_SUBREAD_IPD:
                case SMRT_SUBREAD_PW:
                case SMRT_CCS_FWD_IPD:
                case SMRT_CCS_REV_IPD:
                case SMRT_CCS_FWD_PW:
                case SMRT_CCS_REV_PW:
                    return true;
                default:
                    return false;
            }
        }
    }



    public enum ShadeAlignmentsOption {
        NONE("none"),
        MAPPING_QUALITY_HIGH("mapping quality high"),
        MAPPING_QUALITY_LOW("mapping quality low");

        public final String label;

        ShadeAlignmentsOption(String label) {
            this.label = label;
        }
    }

    public enum GroupOption {
        STRAND("read strand"),
        SAMPLE("sample"),
        READ_GROUP("read group"),
        LIBRARY("library"),
        FIRST_OF_PAIR_STRAND("first-in-pair strand"),
        TAG("tag"),
        PAIR_ORIENTATION("pair orientation"),
        MATE_CHROMOSOME("chromosome of mate"),
        NONE("none"),
        SUPPLEMENTARY("supplementary flag"),
        BASE_AT_POS("base at position"),
        MOVIE("movie"),
        ZMW("ZMW"),
        HAPLOTYPE("haplotype"),
        READ_ORDER("read order"),
        LINKED("linked"),
        PHASE("phase"),
        REFERENCE_CONCORDANCE("reference concordance"),
        MAPPING_QUALITY("mapping quality");

        public final String label;

        GroupOption(String label) {
            this.label = label;
        }

    }

    enum OrientationType {
        RR, LL, RL, LR, UNKNOWN
    }

    private static final int GROUP_LABEL_HEIGHT = 10;
    private static final int GROUP_MARGIN = 5;
    private static final int TOP_MARGIN = 20;
    private static final int DS_MARGIN_0 = 2;
    private static final int DOWNAMPLED_ROW_HEIGHT = 3;
    private static final int INSERTION_ROW_HEIGHT = 9;
    private static final int DS_MARGIN_2 = 5;

    public enum BisulfiteContext {
        CG("CG", new byte[]{}, new byte[]{'G'}),
        CHH("CHH", new byte[]{}, new byte[]{'H', 'H'}),
        CHG("CHG", new byte[]{}, new byte[]{'H', 'G'}),
        HCG("HCG", new byte[]{'H'}, new byte[]{'G'}),
        GCH("GCH", new byte[]{'G'}, new byte[]{'H'}),
        WCG("WCG", new byte[]{'W'}, new byte[]{'G'}),
        NONE("None", null, null);

        private final String label;
        private final byte[] preContext;
        private final byte[] postContext;

        /**
         * @param contextb      The residue in the context string (IUPAC)
         * @param referenceBase The reference sequence (already checked that offsetidx is within bounds)
         * @param readBase      The read sequence (already checked that offsetidx is within bounds)
         */
        private static boolean positionMatchesContext(byte contextb, final byte referenceBase, final byte readBase) {
            boolean matchesContext = AlignmentUtils.compareBases(contextb, referenceBase);
            if (!matchesContext) {
                return false; // Don't need to check any further
            }

            // For the read, we have to handle C separately
            boolean matchesReadContext = AlignmentUtils.compareBases(contextb, readBase);
            if (AlignmentUtils.compareBases((byte) 'T', readBase)) {
                matchesReadContext |= AlignmentUtils.compareBases(contextb, (byte) 'C');
            }

            return matchesReadContext;
        }

        public BisulfiteContext getMatchingBisulfiteContext(final byte[] reference, final ByteSubarray read, final int idx) {
            boolean matchesContext = true;

            // First do the "post" context
            int minLen = Math.min(reference.length, read.length);
            if ((idx + postContext.length) >= minLen) {
                matchesContext = false;
            } else {
                // Cut short whenever we don't match
                for (int posti = 0; matchesContext && (posti < postContext.length); posti++) {
                    byte contextb = postContext[posti];
                    int offsetidx = idx + 1 + posti;

                    matchesContext &= positionMatchesContext(contextb, reference[offsetidx], read.getByte(offsetidx));
                }
            }

            // Now do the pre context
            if ((idx - preContext.length) < 0) {
                matchesContext = false;
            } else {
                // Cut short whenever we don't match
                for (int prei = 0; matchesContext && (prei < preContext.length); prei++) {
                    byte contextb = preContext[prei];
                    int offsetidx = idx - (preContext.length - prei);

                    matchesContext &= positionMatchesContext(contextb, reference[offsetidx], read.getByte(offsetidx));
                }
            }

            return (matchesContext) ? this : null;
        }

        public String getLabel() { return label; }

        BisulfiteContext(String label, byte[] preContext, byte[] postContext){
            this.label = label;
            this.preContext = preContext;
            this.postContext = postContext;
        }
    }

    public static boolean isBisulfiteColorType(ColorOption o) {
        return (o.equals(ColorOption.BISULFITE) || o.equals(ColorOption.NOMESEQ));
    }

    private final AlignmentDataManager dataManager;
    private final SequenceTrack sequenceTrack;
    private final CoverageTrack coverageTrack;
    private final SpliceJunctionTrack spliceJunctionTrack;

    private final Genome genome;
    private ExperimentType experimentType;
    private final AlignmentRenderer renderer;
    private RenderOptions renderOptions;

    private boolean removed = false;
    private boolean showGroupLine;
    private Map<ReferenceFrame, List<InsertionInterval>> insertionIntervalsMap;
    private int expandedHeight = 14;
    private int collapsedHeight = 9;
    private final int maxSquishedHeight = 5;
    private int squishedHeight = maxSquishedHeight;
    private final int minHeight = 50;

    private Rectangle alignmentsRect;
    private Rectangle downsampleRect;
    private Rectangle insertionRect;
    private ColorTable readNamePalette;
    private final HashMap<String, Color> selectedReadNames = new HashMap<>();


    /**
     * Create a new alignment track
     *
     * @param locator
     * @param dataManager
     * @param genome
     */
    public AlignmentTrack(ResourceLocator locator, AlignmentDataManager dataManager, Genome genome) {
        super(locator);

        final String baseName = locator.getTrackName();
        this.setName(baseName);
        this.dataManager = dataManager;
        this.genome = genome;
        this.renderer = new AlignmentRenderer(this);
        this.renderOptions = new RenderOptions(this);
        setColor(DEFAULT_ALIGNMENT_COLOR);
        dataManager.setAlignmentTrack(this);
        dataManager.subscribe(this);

        IGVPreferences prefs = getPreferences();
        minimumHeight = 50;
        showGroupLine = prefs.getAsBoolean(SAM_SHOW_GROUP_SEPARATOR);
        try {
            setDisplayMode(DisplayMode.valueOf(prefs.get(SAM_DISPLAY_MODE).toUpperCase()));
        } catch (Exception e) {
            setDisplayMode(DisplayMode.EXPANDED);
        }

        // Optional sequence track (not common)
        if (prefs.getAsBoolean(SAM_SHOW_REF_SEQ)) {
            sequenceTrack = new SequenceTrack("Reference sequence");
            sequenceTrack.setHeight(14);
        } else {
            sequenceTrack = null;
        }

        // Coverage track
        this.coverageTrack = new CoverageTrack(locator, baseName + " Coverage", this, genome);
        this.coverageTrack.setDataManager(dataManager);
        dataManager.setCoverageTrack(this.coverageTrack);

        // Splice junction track
        SpliceJunctionTrack spliceJunctionTrack = new SpliceJunctionTrack(locator,
                baseName + " Junctions", dataManager, this, SpliceJunctionTrack.StrandOption.BOTH);
        spliceJunctionTrack.setHeight(60);
        this.spliceJunctionTrack = spliceJunctionTrack;

        if (renderOptions.colorOption == ColorOption.BISULFITE) {
            setExperimentType(ExperimentType.BISULFITE);
        }
        readNamePalette = new PaletteColorTable(ColorUtilities.getDefaultPalette());
        insertionIntervalsMap = Collections.synchronizedMap(new HashMap<>());

        dataManager.setViewAsPairs(prefs.getAsBoolean(SAM_DISPLAY_PAIRED), renderOptions);

        IGVEventBus.getInstance().subscribe(FrameManager.ChangeEvent.class, this);
        IGVEventBus.getInstance().subscribe(AlignmentTrackEvent.class, this);
    }

    public void init() {
        if (experimentType == null || experimentType == ExperimentType.UNKOWN) {
            ExperimentType type = dataManager.inferType();
            setExperimentType(type);
        }
    }


    @Override
    public void receiveEvent(Object event) {

        if (event instanceof FrameManager.ChangeEvent) {
            // Trim insertionInterval map to current frames
            Map<ReferenceFrame, List<InsertionInterval>> newMap = Collections.synchronizedMap(new HashMap<>());
            for (ReferenceFrame frame : ((FrameManager.ChangeEvent) event).getFrames()) {
                if (insertionIntervalsMap.containsKey(frame)) {
                    newMap.put(frame, insertionIntervalsMap.get(frame));
                }
            }
            insertionIntervalsMap = newMap;

        } else if (event instanceof AlignmentTrackEvent) {
            AlignmentTrackEvent e = (AlignmentTrackEvent) event;
            AlignmentTrackEvent.Type eventType = e.getType();
            switch (eventType) {
                case ALLELE_THRESHOLD:
                    dataManager.alleleThresholdChanged();
                    break;
                case RELOAD:
                    clearCaches();
                    repaint();
                case REFRESH:
                    repaint();
                    break;
            }

        }
    }

    void setExperimentType(ExperimentType type) {

        if (type != experimentType) {

            experimentType = type;

            boolean showJunction = getPreferences(type).getAsBoolean(Constants.SAM_SHOW_JUNCTION_TRACK);
            if (showJunction != spliceJunctionTrack.isVisible()) {
                spliceJunctionTrack.setVisible(showJunction);
                if (IGV.hasInstance()) {
                    IGV.getInstance().revalidateTrackPanels();
                }
            }

            boolean showCoverage = getPreferences(type).getAsBoolean(SAM_SHOW_COV_TRACK);
            if (showCoverage != coverageTrack.isVisible()) {
                coverageTrack.setVisible(showCoverage);
                if (IGV.hasInstance()) {
                    IGV.getInstance().revalidateTrackPanels();
                }
            }

            boolean showAlignments = getPreferences(type).getAsBoolean(SAM_SHOW_ALIGNMENT_TRACK);
            if (showAlignments != isVisible()) {
                setVisible(showAlignments);
                if (IGV.hasInstance()) {
                    IGV.getInstance().revalidateTrackPanels();
                }
            }
            //ExperimentTypeChangeEvent event = new ExperimentTypeChangeEvent(this, experimentType);
            //IGVEventBus.getInstance().post(event);
        }
    }

    ExperimentType getExperimentType() {
        return experimentType;
    }

    public AlignmentDataManager getDataManager() {
        return dataManager;
    }

    public CoverageTrack getCoverageTrack() {
        return coverageTrack;
    }

    public SpliceJunctionTrack getSpliceJunctionTrack() {
        return spliceJunctionTrack;
    }

    public RenderOptions getRenderOptions() {
        return renderOptions;
    }

    public HashMap<String, Color> getSelectedReadNames() {
        return selectedReadNames;
    }

    public ColorTable getReadNamePalette() {
        return readNamePalette;
    }

    @Override
    public IGVPopupMenu getPopupMenu(TrackClickEvent te) {
        return new AlignmentTrackMenu(this, te);
    }

    @Override
    public void setHeight(int preferredHeight) {
        super.setHeight(preferredHeight);
        minimumHeight = preferredHeight;
    }

    @Override
    public int getHeight() {

        int nGroups = dataManager.getMaxGroupCount();
        int h = Math.max(minHeight, getNLevels() * getRowHeight() + nGroups * GROUP_MARGIN + TOP_MARGIN
                + DS_MARGIN_0 + DOWNAMPLED_ROW_HEIGHT + DS_MARGIN_2);
        //if (insertionRect != null) {   // TODO - replace with expand insertions preference
        h += INSERTION_ROW_HEIGHT + DS_MARGIN_0;
        //}
        return Math.max(minimumHeight, h);
    }

    private int getRowHeight() {
        if (getDisplayMode() == DisplayMode.EXPANDED) {
            return expandedHeight;
        } else if (getDisplayMode() == DisplayMode.COLLAPSED) {
            return collapsedHeight;
        } else {
            return squishedHeight;
        }
    }

    private int getNLevels() {
        return dataManager.getNLevels();
    }

    @Override
    public boolean isReadyToPaint(ReferenceFrame frame) {

        if (frame.getChrName().equals(Globals.CHR_ALL) || frame.getScale() > dataManager.getMinVisibleScale()) {
            return true;   // Nothing to paint
        } else {
            List<InsertionInterval> insertionIntervals = getInsertionIntervals(frame);
            insertionIntervals.clear();
            return dataManager.isLoaded(frame);
        }
    }


    @Override
    public void load(ReferenceFrame referenceFrame) {
        if (log.isDebugEnabled()) {
            log.debug("Reading - thread: " + Thread.currentThread().getName());
        }
        dataManager.load(referenceFrame, renderOptions, true);
    }

    @Override
    public int getVisibilityWindow() {
        return (int) dataManager.getVisibilityWindow();
    }

    public void render(RenderContext context, Rectangle rect) {

        int viewWindowSize = context.getReferenceFrame().getCurrentRange().getLength();
        if (viewWindowSize > getVisibilityWindow()) {
            Rectangle visibleRect = context.getVisibleRect().intersection(rect);
            Graphics2D g2 = context.getGraphic2DForColor(Color.gray);
            GraphicUtils.drawCenteredText("Zoom in to see alignments.", visibleRect, g2);
            return;
        }

        context.getGraphics2D("LABEL").setFont(FontManager.getFont(GROUP_LABEL_HEIGHT));

        // Split track rectangle into sections.
        int seqHeight = sequenceTrack == null ? 0 : sequenceTrack.getHeight();
        if (seqHeight > 0) {
            Rectangle seqRect = new Rectangle(rect);
            seqRect.height = seqHeight;
            sequenceTrack.render(context, seqRect);
        }

        // Top gap.
        rect.y += DS_MARGIN_0;

        downsampleRect = new Rectangle(rect);
        downsampleRect.height = DOWNAMPLED_ROW_HEIGHT;
        renderDownsampledIntervals(context, downsampleRect);

        if (renderOptions.isShowInsertionMarkers()) {
            insertionRect = new Rectangle(rect);
            insertionRect.y += DOWNAMPLED_ROW_HEIGHT + DS_MARGIN_0;
            insertionRect.height = INSERTION_ROW_HEIGHT;
            renderInsertionIntervals(context, insertionRect);
            rect.y = insertionRect.y + insertionRect.height;
        }

        alignmentsRect = new Rectangle(rect);
        alignmentsRect.y += 2;
        alignmentsRect.height -= (alignmentsRect.y - rect.y);
        renderAlignments(context, alignmentsRect);
    }

    private void renderDownsampledIntervals(RenderContext context, Rectangle downsampleRect) {

        // Might be offscreen
        if (!context.getVisibleRect().intersects(downsampleRect)) return;

        final AlignmentInterval loadedInterval = dataManager.getLoadedInterval(context.getReferenceFrame());
        if (loadedInterval == null) return;

        Graphics2D g = context.getGraphic2DForColor(Color.black);

        List<DownsampledInterval> intervals = loadedInterval.getDownsampledIntervals();
        for (DownsampledInterval interval : intervals) {
            final double scale = context.getScale();
            final double origin = context.getOrigin();

            int x0 = (int) ((interval.getStart() - origin) / scale);
            int x1 = (int) ((interval.getEnd() - origin) / scale);
            int w = Math.max(1, x1 - x0);
            // If there is room, leave a gap on one side
            if (w > 5) w--;
            // Greyscale from 0 -> 100 downsampled
            //int gray = 200 - interval.getCount();
            //Color color = (gray <= 0 ? Color.black : ColorUtilities.getGrayscaleColor(gray));
            g.fillRect(x0, downsampleRect.y, w, downsampleRect.height);
        }
    }


    private void renderAlignments(RenderContext context, Rectangle inputRect) {

        final AlignmentInterval loadedInterval = dataManager.getLoadedInterval(context.getReferenceFrame(), true);
        if (loadedInterval == null) {
            return;
        }

        final AlignmentCounts alignmentCounts = loadedInterval.getCounts();

        //log.debug("Render features");
        PackedAlignments groups = dataManager.getGroups(loadedInterval, renderOptions);
        if (groups == null) {
            //Assume we are still loading.
            //This might not always be true
            return;
        }

        // Check for YC tag
        if (renderOptions.colorOption == null && dataManager.hasYCTags()) {
            renderOptions.colorOption = ColorOption.YC_TAG;
        }

        Map<String, PEStats> peStats = dataManager.getPEStats();
        if (peStats != null) {
            renderOptions.peStats = peStats;
        }

        Rectangle visibleRect = context.getVisibleRect();

        // Divide rectangle into equal height levels
        double y = inputRect.getY();
        double h;
        if (getDisplayMode() == DisplayMode.EXPANDED) {
            h = expandedHeight;
        } else if (getDisplayMode() == DisplayMode.COLLAPSED) {
            h = collapsedHeight;
        } else {

            int visHeight = visibleRect.height;
            int depth = dataManager.getNLevels();
            if (depth == 0) {
                squishedHeight = Math.min(maxSquishedHeight, Math.max(1, expandedHeight));
            } else {
                squishedHeight = Math.min(maxSquishedHeight, Math.max(1, Math.min(expandedHeight, visHeight / depth)));
            }
            h = squishedHeight;
        }


        // Loop through groups
        Graphics2D groupBorderGraphics = context.getGraphic2DForColor(AlignmentRenderer.GROUP_DIVIDER_COLOR);
        int nGroups = groups.size();
        int groupNumber = 0;
        GroupOption groupOption = renderOptions.getGroupByOption();
        for (Map.Entry<String, List<Row>> entry : groups.entrySet()) {

            groupNumber++;
            double yGroup = y;  // Remember this for label

            // Loop through the alignment rows for this group
            List<Row> rows = entry.getValue();
            for (Row row : rows) {
                if ((visibleRect != null && y > visibleRect.getMaxY())) {
                    break;
                }

                assert visibleRect != null;
                if (y + h > visibleRect.getY()) {
                    Rectangle rowRectangle = new Rectangle(inputRect.x, (int) y, inputRect.width, (int) h);
                    renderer.renderAlignments(row.alignments, alignmentCounts, context, rowRectangle, renderOptions);
                    row.y = y;
                    row.h = h;
                }
                y += h;
            }

            if (groupOption != GroupOption.NONE) {
                // Draw a subtle divider line between groups
                if (showGroupLine) {
                    if (groupNumber < nGroups) {
                        int borderY = (int) y + GROUP_MARGIN / 2;
                        GraphicUtils.drawDottedDashLine(groupBorderGraphics, inputRect.x, borderY, inputRect.width, borderY);
                    }
                }

                // Label the group, if there is room
                double groupHeight = rows.size() * h;
                if (groupHeight > GROUP_LABEL_HEIGHT + 2) {
                    String groupName = entry.getKey();
                    Graphics2D g = context.getGraphics2D("LABEL");
                    FontMetrics fm = g.getFontMetrics();
                    Rectangle2D stringBouds = fm.getStringBounds(groupName, g);
                    Rectangle rect = new Rectangle(inputRect.x, (int) yGroup,
                            (int) stringBouds.getWidth() + 10, (int) stringBouds.getHeight());
                    GraphicUtils.drawVerticallyCenteredText(groupName, 5, rect, g, false, true);
                }
            }
            y += GROUP_MARGIN;
        }

        final int bottom = inputRect.y + inputRect.height;
        groupBorderGraphics.drawLine(inputRect.x, bottom, inputRect.width, bottom);
    }

    private List<InsertionInterval> getInsertionIntervals(ReferenceFrame frame) {
        List<InsertionInterval> insertionIntervals = insertionIntervalsMap.computeIfAbsent(frame, k -> new ArrayList<>());
        return insertionIntervals;
    }

    private void renderInsertionIntervals(RenderContext context, Rectangle rect) {

        // Might be offscreen
        if (!context.getVisibleRect().intersects(rect)) return;

        List<InsertionMarker> intervals = context.getInsertionMarkers();
        if (intervals == null) return;

        InsertionMarker selected = InsertionManager.getInstance().getSelectedInsertion(context.getChr());

        int w = (int) ((1.41 * rect.height) / 2);


        boolean hideSmallIndels = renderOptions.isHideSmallIndels();
        int smallIndelThreshold = renderOptions.getSmallIndelThreshold();

        List<InsertionInterval> insertionIntervals = getInsertionIntervals(context.getReferenceFrame());
        insertionIntervals.clear();
        for (InsertionMarker insertionMarker : intervals) {
            if (hideSmallIndels && insertionMarker.size < smallIndelThreshold) continue;

            final double scale = context.getScale();
            final double origin = context.getOrigin();
            int midpoint = (int) ((insertionMarker.position - origin) / scale);
            int x0 = midpoint - w;
            int x1 = midpoint + w;

            Rectangle iRect = new Rectangle(x0 + context.translateX, rect.y, 2 * w, rect.height);

            insertionIntervals.add(new InsertionInterval(iRect, insertionMarker));

            Color c = (selected != null && selected.position == insertionMarker.position) ? new Color(200, 0, 0, 80) : AlignmentRenderer.purple;
            Graphics2D g = context.getGraphic2DForColor(c);


            g.fillPolygon(new Polygon(new int[]{x0, x1, midpoint},
                    new int[]{rect.y, rect.y, rect.y + rect.height}, 3));
        }
    }

    public void renderExpandedInsertion(InsertionMarker insertionMarker, RenderContext context, Rectangle inputRect) {


        boolean leaveMargin = getDisplayMode() != DisplayMode.SQUISHED;

        // Insertion interval
        if(this.renderOptions.isShowInsertionMarkers()) {
            Graphics2D g = context.getGraphic2DForColor(Color.red);
            Rectangle iRect = new Rectangle(inputRect.x, insertionRect.y, inputRect.width, insertionRect.height);
            g.fill(iRect);

            List<InsertionInterval> insertionIntervals = getInsertionIntervals(context.getReferenceFrame());

            iRect.x += context.translateX;
            insertionIntervals.add(new InsertionInterval(iRect, insertionMarker));
        }


        inputRect.y += DS_MARGIN_0 + DOWNAMPLED_ROW_HEIGHT + DS_MARGIN_0 + INSERTION_ROW_HEIGHT + DS_MARGIN_2;

        //log.debug("Render features");
        final AlignmentInterval loadedInterval = dataManager.getLoadedInterval(context.getReferenceFrame(), true);
        PackedAlignments groups = dataManager.getGroups(loadedInterval, renderOptions);
        if (groups == null) {
            //Assume we are still loading.
            //This might not always be true
            return;
        }

        Rectangle visibleRect = context.getVisibleRect();

        // Divide rectangle into equal height levels
        double y = inputRect.getY() - 3;
        double h;
        if (getDisplayMode() == DisplayMode.EXPANDED) {
            h = expandedHeight;
        } else if (getDisplayMode() == DisplayMode.COLLAPSED) {
            h = collapsedHeight;
        } else {
            int visHeight = visibleRect.height;
            int depth = dataManager.getNLevels();
            if (depth == 0) {
                squishedHeight = Math.min(maxSquishedHeight, Math.max(1, expandedHeight));
            } else {
                squishedHeight = Math.min(maxSquishedHeight, Math.max(1, Math.min(expandedHeight, visHeight / depth)));
            }
            h = squishedHeight;
        }


        for (Map.Entry<String, List<Row>> entry : groups.entrySet()) {


            // Loop through the alignment rows for this group
            List<Row> rows = entry.getValue();
            for (Row row : rows) {
                if ((visibleRect != null && y > visibleRect.getMaxY())) {
                    return;
                }

                assert visibleRect != null;
                if (y + h > visibleRect.getY()) {
                    Rectangle rowRectangle = new Rectangle(inputRect.x, (int) y, inputRect.width, (int) h);
                    renderer.renderExpandedInsertion(insertionMarker, row.alignments, context, rowRectangle, leaveMargin);
                    row.y = y;
                    row.h = h;
                }
                y += h;
            }

            y += GROUP_MARGIN;


        }

    }

    private InsertionInterval getInsertionInterval(ReferenceFrame frame, int x, int y) {
        List<InsertionInterval> insertionIntervals = getInsertionIntervals(frame);
        for (InsertionInterval i : insertionIntervals) {
            if (i.rect.contains(x, y)) return i;
        }

        return null;
    }

    public void sortRows(final SortOption option, final Double location, final String tag, final boolean invertSort) {
        final List<ReferenceFrame> frames = FrameManager.getFrames();
        for (ReferenceFrame frame : frames) {
            final double actloc = location != null ? location : frame.getCenter();
            final AlignmentInterval interval = getDataManager().getLoadedInterval(frame);
            if(interval != null) {
                interval.sortRows(option, actloc, tag, invertSort);
            } else {
                log.warn("Attempt to sort alignments prior to loading");
            }
        }
    }

    void sortAlignmentTracks(SortOption option, String tag, boolean invertSort) {
        IGV.getInstance().sortAlignmentTracks(option, tag, invertSort);
        Collection<IGVPreferences> allPrefs = PreferencesManager.getAllPreferences();
        for (IGVPreferences prefs : allPrefs) {
            prefs.put(SAM_SORT_OPTION, option.toString());
            prefs.put(SAM_SORT_BY_TAG, tag);
            prefs.put(SAM_INVERT_SORT, invertSort);
        }
    }

    /**
     * Visually regroup alignments by the provided {@code GroupOption}.
     *
     * @param option
     * @see AlignmentDataManager#packAlignments
     */
    public void groupAlignments(GroupOption option, String tag, Range pos) {
        if (option == GroupOption.TAG && tag != null) {
            renderOptions.setGroupByTag(tag);
        }
        if (option == GroupOption.BASE_AT_POS && pos != null) {
            renderOptions.setGroupByPos(pos);
        }
        renderOptions.setGroupByOption(option);
        dataManager.packAlignments(renderOptions);
        repaint();

    }

    public void setBisulfiteContext(BisulfiteContext option) {
        renderOptions.bisulfiteContext = option;
        getPreferences().put(SAM_BISULFITE_CONTEXT, option.toString());
    }

    public void setColorOption(ColorOption option) {
        renderOptions.setColorOption(option);
    }

    public void setColorByTag(String tag) {
        renderOptions.setColorByTag(tag);
        getPreferences(experimentType).put(SAM_COLOR_BY_TAG, tag);
    }

    public void setShadeAlignmentsOptions(ShadeAlignmentsOption option) {
        renderOptions.setShadeAlignmentsOption(option);
    }

    public void packAlignments() {
        dataManager.packAlignments(renderOptions);
    }


    public boolean isLogNormalized() {
        return false;
    }

    public float getRegionScore(String chr, int start, int end, int zoom, RegionScoreType type, String frameName) {
        return 0.0f;
    }


    public String getValueStringAt(String chr, double position, int mouseX, int mouseY, ReferenceFrame frame) {

        if (downsampleRect != null && mouseY > downsampleRect.y && mouseY <= downsampleRect.y + downsampleRect.height) {
            AlignmentInterval loadedInterval = dataManager.getLoadedInterval(frame);
            if (loadedInterval == null) {
                return null;
            } else {
                List<DownsampledInterval> intervals = loadedInterval.getDownsampledIntervals();
                DownsampledInterval interval = FeatureUtils.getFeatureAt(position, 0, intervals);
                if (interval != null) {
                    return interval.getValueString();
                }
                return null;
            }
        } else {

            InsertionInterval insertionInterval = getInsertionInterval(frame, mouseX, mouseY);
            if (insertionInterval != null) {
                return "Insertions (" + insertionInterval.insertionMarker.size + " bases)";
            } else {
                Alignment feature = getAlignmentAt(position, mouseY, frame);
                if (feature != null) {
                    return feature.getAlignmentValueString(position, mouseX, renderOptions);
                }
            }

        }
        return null;
    }



    Alignment getAlignmentAt(final TrackClickEvent te) {
        MouseEvent e = te.getMouseEvent();
        final ReferenceFrame frame = te.getFrame();
        return frame == null ? null : getAlignmentAt(frame.getChromosomePosition(e), e.getY(), frame);
    }

    Alignment getAlignmentAt(double position, int y, ReferenceFrame frame) {

        if (alignmentsRect == null || dataManager == null) {
            return null;   // <= not loaded yet
        }
        PackedAlignments groups = dataManager.getGroupedAlignmentsContaining(position, frame);

        if (groups == null || groups.isEmpty()) {
            return null;
        }

        for (List<Row> rows : groups.values()) {
            for (Row row : rows) {
                if (y >= row.y && y <= row.y + row.h) {
                    List<Alignment> features = row.alignments;

                    // No buffer for alignments,  you must zoom in far enough for them to be visible
                    int buffer = 0;
                    return FeatureUtils.getFeatureAt(position, buffer, features);
                }
            }
        }
        return null;
    }


    @Override
    public boolean handleDataClick(TrackClickEvent te) {

        MouseEvent e = te.getMouseEvent();
        if (Globals.IS_MAC && e.isMetaDown() || (!Globals.IS_MAC && e.isControlDown())) {
            // Selection
            Alignment alignment = this.getAlignmentAt(te);
            if (alignment != null) {
                if (selectedReadNames.containsKey(alignment.getReadName())) {
                    selectedReadNames.remove(alignment.getReadName());
                } else {
                    setSelectedAlignment(alignment);
                }
                IGV.getInstance().repaint(this); //todo check if doing this conditionally here is ok
            }
            return true;
        }

        InsertionInterval insertionInterval = getInsertionInterval(te.getFrame(), te.getMouseEvent().getX(), te.getMouseEvent().getY());
        if (insertionInterval != null) {

            final String chrName = te.getFrame().getChrName();
            InsertionMarker currentSelection = InsertionManager.getInstance().getSelectedInsertion(chrName);
            if (currentSelection != null && currentSelection.position == insertionInterval.insertionMarker.position) {
                InsertionManager.getInstance().clearSelected();
            } else {
                InsertionManager.getInstance().setSelected(chrName, insertionInterval.insertionMarker.position);
            }

            IGVEventBus.getInstance().post(new InsertionSelectionEvent(insertionInterval.insertionMarker));

            return true;
        }


        if (IGV.getInstance().isShowDetailsOnClick()) {
            openTooltipWindow(te);
            return true;
        }

        return false;
    }

    void setSelectedAlignment(Alignment alignment) {
        Color c = readNamePalette.get(alignment.getReadName());
        selectedReadNames.put(alignment.getReadName(), c);
    }

    private void clearCaches() {
        if (dataManager != null) dataManager.clear();
        if (spliceJunctionTrack != null) spliceJunctionTrack.clear();
    }


    public void setViewAsPairs(boolean vAP) {
        // TODO -- generalize this test to all incompatible pairings
        if (vAP && renderOptions.groupByOption == GroupOption.STRAND) {
            boolean ungroup = MessageUtils.confirm("\"View as pairs\" is incompatible with \"Group by strand\". Ungroup?");
            if (ungroup) {
                renderOptions.setGroupByOption(null);
            } else {
                return;
            }
        }

        dataManager.setViewAsPairs(vAP, renderOptions);
        repaint();
    }


    public enum ExperimentType {OTHER, RNA, BISULFITE, THIRD_GEN, UNKOWN}


    class RenderRollback {
        final ColorOption colorOption;
        final GroupOption groupByOption;
        final String groupByTag;
        final String colorByTag;
        final String linkByTag;
        final DisplayMode displayMode;
        final int expandedHeight;
        final boolean showGroupLine;

        RenderRollback(RenderOptions renderOptions, DisplayMode displayMode) {
            this.colorOption = renderOptions.colorOption;
            this.groupByOption = renderOptions.groupByOption;
            this.colorByTag = renderOptions.colorByTag;
            this.groupByTag = renderOptions.groupByTag;
            this.displayMode = displayMode;
            this.expandedHeight = AlignmentTrack.this.expandedHeight;
            this.showGroupLine = AlignmentTrack.this.showGroupLine;
            this.linkByTag = renderOptions.linkByTag;
        }

        void restore(RenderOptions renderOptions) {
            renderOptions.colorOption = this.colorOption;
            renderOptions.groupByOption = this.groupByOption;
            renderOptions.colorByTag = this.colorByTag;
            renderOptions.groupByTag = this.groupByTag;
            renderOptions.linkByTag = this.linkByTag;
            AlignmentTrack.this.expandedHeight = this.expandedHeight;
            AlignmentTrack.this.showGroupLine = this.showGroupLine;
            AlignmentTrack.this.setDisplayMode(this.displayMode);
        }
    }

    public boolean isRemoved() {
        return removed;
    }

    @Override
    public boolean isVisible() {
        return super.isVisible() && !removed;
    }

    IGVPreferences getPreferences() {
        return getPreferences(experimentType);
    }

    public static IGVPreferences getPreferences(ExperimentType type) {

        try {
            // Disable experimentType preferences for 2.4
            if (Globals.VERSION.contains("2.4")) {
                return PreferencesManager.getPreferences(NULL_CATEGORY);
            } else {
                String prefKey = Constants.NULL_CATEGORY;
                if (type == ExperimentType.THIRD_GEN) {
                    prefKey = Constants.THIRD_GEN;
                } else if (type == ExperimentType.RNA) {
                    prefKey = Constants.RNA;
                }
                return PreferencesManager.getPreferences(prefKey);
            }
        } catch (NullPointerException e) {
            String prefKey = Constants.NULL_CATEGORY;
            if (type == ExperimentType.THIRD_GEN) {
                prefKey = Constants.THIRD_GEN;
            } else if (type == ExperimentType.RNA) {
                prefKey = Constants.RNA;
            }
            return PreferencesManager.getPreferences(prefKey);
        }
    }


    @Override
    public void unload() {
        super.unload();
        if (dataManager != null) {
            dataManager.unsubscribe(this);
        }
        removed = true;
        setVisible(false);
    }

    boolean isLinkedReads() {
        return renderOptions != null && renderOptions.isLinkedReads();
    }

    /**
     * Set the view to a 10X style "linked-read view".  This option tries to achieve a view similar to the
     * 10X Loupe view described here: https://support.10xgenomics.com/genome-exome/software/visualization/latest/linked-reads
     *
     * @param linkedReads
     * @param tag
     */
    void setLinkedReadView(boolean linkedReads, String tag) {
        if (!linkedReads || isLinkedReadView()) {
            undoLinkedReadView();
        }
        renderOptions.setLinkedReads(linkedReads);
        if (linkedReads) {
            renderOptions.setLinkByTag(tag);
            renderOptions.setColorOption(ColorOption.TAG);
            renderOptions.setColorByTag(tag);
            if (dataManager.isPhased()) {
                renderOptions.setGroupByOption(GroupOption.TAG);
                renderOptions.setGroupByTag("HP");
            }
            showGroupLine = false;
            setDisplayMode(DisplayMode.SQUISHED);
        }
        dataManager.packAlignments(renderOptions);
        repaint();
    }

    /**
     * Detect if we are in linked-read view
     */
    boolean isLinkedReadView() {
        return renderOptions != null &&
                renderOptions.isLinkedReads() &&
                renderOptions.getLinkByTag() != null &&
                renderOptions.getColorOption() == ColorOption.TAG &&
                renderOptions.getColorByTag() != null;
    }

    void undoLinkedReadView() {
        renderOptions.setLinkByTag(null);
        renderOptions.setColorOption(ColorOption.NONE);
        renderOptions.setColorByTag(null);
        renderOptions.setGroupByOption(GroupOption.NONE);
        renderOptions.setGroupByTag(null);
        showGroupLine = true;
        setDisplayMode(DisplayMode.EXPANDED);
    }

    void sendPairsToCircularView(TrackClickEvent e) {

        List<ReferenceFrame> frames = e.getFrame() != null ?
                Arrays.asList(e.getFrame()) :
                FrameManager.getFrames();

        List<Alignment> inView = new ArrayList<>();
        for (ReferenceFrame frame : frames) {
            AlignmentInterval interval = AlignmentTrack.this.getDataManager().getLoadedInterval(frame);
            if (interval != null) {
                Iterator<Alignment> iter = interval.getAlignmentIterator();
                Range r = frame.getCurrentRange();
                while (iter.hasNext()) {
                    Alignment a = iter.next();
                    if (a.getEnd() > r.getStart() && a.getStart() < r.getEnd()) {
                        final boolean isDiscordantPair = a.isPaired() && a.getMate().isMapped() &&
                                (!a.getMate().getChr().equals(a.getChr()) || Math.abs(a.getInferredInsertSize()) > 10000);
                        if (isDiscordantPair) {
                            inView.add(a);
                        }
                    }
                }
            }
            Color chordColor = AlignmentTrack.this.getColor().equals(DEFAULT_ALIGNMENT_COLOR) ? Color.BLUE : AlignmentTrack.this.getColor();
            CircularViewUtilities.sendAlignmentsToJBrowse(inView, AlignmentTrack.this.getName(), chordColor);
        }
    }

    void sendSplitToCircularView(TrackClickEvent e) {

        List<ReferenceFrame> frames = e.getFrame() != null ?
                Arrays.asList(e.getFrame()) :
                FrameManager.getFrames();

        List<Alignment> inView = new ArrayList<>();
        for (ReferenceFrame frame : frames) {
            AlignmentInterval interval = AlignmentTrack.this.getDataManager().getLoadedInterval(frame);
            if (interval != null) {
                Iterator<Alignment> iter = interval.getAlignmentIterator();
                Range r = frame.getCurrentRange();
                while (iter.hasNext()) {
                    Alignment a = iter.next();
                    if (a.getEnd() > r.getStart() && a.getStart() < r.getEnd() && a.getAttribute("SA") != null) {
                        inView.add(a);
                    }
                }
            }
            Color chordColor = AlignmentTrack.this.getColor().equals(DEFAULT_ALIGNMENT_COLOR) ? Color.BLUE : AlignmentTrack.this.getColor();
            CircularViewUtilities.sendAlignmentsToJBrowse(inView, AlignmentTrack.this.getName(), chordColor);
        }
    }

    private static class InsertionInterval {

        final Rectangle rect;
        final InsertionMarker insertionMarker;

        InsertionInterval(Rectangle rect, InsertionMarker insertionMarker) {
            this.rect = rect;
            this.insertionMarker = insertionMarker;
        }
    }


    AlignmentBlock getInsertion(Alignment alignment, int pixelX) {
        if (alignment != null && alignment.getInsertions() != null) {
            for (AlignmentBlock block : alignment.getInsertions()) {
                if (block.containsPixel(pixelX)) {
                    return block;
                }
            }
        }
        return null;
    }

    @Override
    public void unmarshalXML(Element element, Integer version) {

        super.unmarshalXML(element, version);

        if (element.hasAttribute("experimentType")) {
            experimentType = ExperimentType.valueOf(element.getAttribute("experimentType"));
        }

        NodeList tmp = element.getElementsByTagName("RenderOptions");
        if (tmp.getLength() > 0) {
            Element renderElement = (Element) tmp.item(0);
            renderOptions = new RenderOptions(this);
            renderOptions.unmarshalXML(renderElement, version);
        }
    }


    @Override
    public void marshalXML(Document document, Element element) {

        super.marshalXML(document, element);

        if (experimentType != null) {
            element.setAttribute("experimentType", experimentType.toString());
        }

        Element sourceElement = document.createElement("RenderOptions");
        renderOptions.marshalXML(document, sourceElement);
        element.appendChild(sourceElement);

    }

    static class InsertionMenu extends IGVPopupMenu {

        final AlignmentBlock insertion;

        InsertionMenu(AlignmentBlock insertion) {

            this.insertion = insertion;

            addCopySequenceItem();

            if (insertion.getBases() != null && insertion.getBases().length > 10) {
                addBlatItem();
            }
        }


        void addCopySequenceItem() {
            // Change track height by attribute
            final JMenuItem item = new JMenuItem("Copy insert sequence");
            add(item);
            item.addActionListener(aEvt -> StringUtils.copyTextToClipboard(insertion.getBases().getString()));
        }


        void addBlatItem() {
            // Change track height by attribute
            final JMenuItem item = new JMenuItem("BLAT insert sequence");
            add(item);
            item.addActionListener(aEvt -> {
                String blatSeq = insertion.getBases().getString();
                BlatClient.doBlatQuery(blatSeq, "BLAT insert sequence");
            });
            item.setEnabled(insertion.getBases() != null && insertion.getBases().length >= 10);
        }

        @Override
        public boolean includeStandardItems() {
            return false;
        }
    }

    public static class RenderOptions implements Cloneable, Persistable {

        public static final String NAME = "RenderOptions";

        private AlignmentTrack track;
        private Boolean shadeBasesOption;
        private Boolean shadeCenters;
        private Boolean flagUnmappedPairs;
        private Boolean showAllBases;
        private Integer minInsertSize;
        private Integer maxInsertSize;
        private ColorOption colorOption;
        private SortOption sortOption;
        private GroupOption groupByOption;
        private ShadeAlignmentsOption shadeAlignmentsOption;
        private Integer mappingQualityLow;
        private Integer mappingQualityHigh;
        private boolean viewPairs = false;
        private String colorByTag;
        private String groupByTag;
        private String sortByTag;
        private String linkByTag;
        private Boolean linkedReads;
        private Boolean quickConsensusMode;
        private Boolean showMismatches;
        Boolean computeIsizes;
        private Double minInsertSizePercentile;
        private Double maxInsertSizePercentile;
        private Boolean pairedArcView;
        private Boolean flagZeroQualityAlignments;
        private Range groupByPos;
        private Boolean invertSorting;
        private boolean invertGroupSorting;
        private Boolean showInsertionMarkers;
        private Boolean hideSmallIndels;
        private Integer smallIndelThreshold;

        BisulfiteContext bisulfiteContext = BisulfiteContext.CG;
        Map<String, PEStats> peStats;

        public RenderOptions() {
        }

        RenderOptions(AlignmentTrack track) {
            //updateColorScale();
            this.track = track;
            peStats = new HashMap<>();
        }

        IGVPreferences getPreferences() {
            return this.track != null ? this.track.getPreferences() : AlignmentTrack.getPreferences(ExperimentType.OTHER);
        }

        void setShowAllBases(boolean showAllBases) {
            this.showAllBases = showAllBases;
        }

        void setShowMismatches(boolean showMismatches) {
            this.showMismatches = showMismatches;
        }

        void setMinInsertSize(int minInsertSize) {
            this.minInsertSize = minInsertSize;
            //updateColorScale();
        }

        public void setViewPairs(boolean viewPairs) {
            this.viewPairs = viewPairs;
        }

        void setComputeIsizes(boolean computeIsizes) {
            this.computeIsizes = computeIsizes;
        }


        void setMaxInsertSizePercentile(double maxInsertSizePercentile) {
            this.maxInsertSizePercentile = maxInsertSizePercentile;
        }

        void setMaxInsertSize(int maxInsertSize) {
            this.maxInsertSize = maxInsertSize;
        }

        void setMinInsertSizePercentile(double minInsertSizePercentile) {
            this.minInsertSizePercentile = minInsertSizePercentile;
        }

        void setColorByTag(String colorByTag) {
            this.colorByTag = colorByTag;
        }

        void setColorOption(ColorOption colorOption) {
            this.colorOption = colorOption;
        }

        void setSortOption(SortOption sortOption) {
            this.sortOption = sortOption;
        }

        void setSortByTag(String sortByTag) {
            this.sortByTag = sortByTag;
        }

        void setGroupByTag(String groupByTag) {
            this.groupByTag = groupByTag;
        }

        void setGroupByPos(Range groupByPos) {
            this.groupByPos = groupByPos;
        }

        void setInvertSorting(boolean invertSorting) {
            this.invertSorting = invertSorting;
        }

        void setInvertGroupSorting(boolean invertGroupSorting) {
            this.invertGroupSorting = invertGroupSorting;
        }

        void setLinkByTag(String linkByTag) {
            this.linkByTag = linkByTag;
        }

        void setQuickConsensusMode(boolean quickConsensusMode) {
            this.quickConsensusMode = quickConsensusMode;
        }

        public void setGroupByOption(GroupOption groupByOption) {
            this.groupByOption = (groupByOption == null) ? GroupOption.NONE : groupByOption;
        }

        void setShadeAlignmentsOption(ShadeAlignmentsOption shadeAlignmentsOption) {
            this.shadeAlignmentsOption = shadeAlignmentsOption;
        }

        void setShadeBasesOption(boolean shadeBasesOption) {
            this.shadeBasesOption = shadeBasesOption;
        }

        void setLinkedReads(boolean linkedReads) {
            this.linkedReads = linkedReads;
        }

        public void setShowInsertionMarkers(boolean drawInsertionIntervals) {
            this.showInsertionMarkers = drawInsertionIntervals;
        }

        public void setHideSmallIndels(boolean hideSmallIndels) {
            this.hideSmallIndels = hideSmallIndels;
        }

        public void setSmallIndelThreshold(int smallIndelThreshold) {
            this.smallIndelThreshold = smallIndelThreshold;
        }

        // getters
        public int getMinInsertSize() {
            return minInsertSize == null ? getPreferences().getAsInt(SAM_MIN_INSERT_SIZE_THRESHOLD) : minInsertSize;
        }

        public int getMaxInsertSize() {
            return maxInsertSize == null ? getPreferences().getAsInt(SAM_MAX_INSERT_SIZE_THRESHOLD) : maxInsertSize;
        }

        public boolean isFlagUnmappedPairs() {
            return flagUnmappedPairs == null ? getPreferences().getAsBoolean(SAM_FLAG_UNMAPPED_PAIR) : flagUnmappedPairs;
        }

        public boolean getShadeBasesOption() {
            return shadeBasesOption == null ? getPreferences().getAsBoolean(SAM_SHADE_BASES) : shadeBasesOption;
        }

        public boolean isShowMismatches() {
            return showMismatches == null ? getPreferences().getAsBoolean(SAM_SHOW_MISMATCHES) : showMismatches;
        }

        public boolean isShowAllBases() {
            return showAllBases == null ? getPreferences().getAsBoolean(SAM_SHOW_ALL_BASES) : showAllBases;
        }

        public boolean isShadeCenters() {
            return shadeCenters == null ? getPreferences().getAsBoolean(SAM_SHADE_CENTER) : shadeCenters;
        }

        boolean isShowInsertionMarkers() {
            return showInsertionMarkers == null ? getPreferences().getAsBoolean(SAM_SHOW_INSERTION_MARKERS) : showInsertionMarkers;
        }

        public boolean isFlagZeroQualityAlignments() {
            return flagZeroQualityAlignments == null ? getPreferences().getAsBoolean(SAM_FLAG_ZERO_QUALITY) : flagZeroQualityAlignments;
        }

        public boolean isViewPairs() {
            return viewPairs;
        }

        public boolean isComputeIsizes() {
            return computeIsizes == null ? getPreferences().getAsBoolean(SAM_COMPUTE_ISIZES) : computeIsizes;
        }

        public double getMinInsertSizePercentile() {
            return minInsertSizePercentile == null ? getPreferences().getAsFloat(SAM_MIN_INSERT_SIZE_PERCENTILE) : minInsertSizePercentile;
        }

        public double getMaxInsertSizePercentile() {
            return maxInsertSizePercentile == null ? getPreferences().getAsFloat(SAM_MAX_INSERT_SIZE_PERCENTILE) : maxInsertSizePercentile;
        }

        public ColorOption getColorOption() {
            return colorOption == null ?
                    CollUtils.valueOf(ColorOption.class, getPreferences().get(SAM_COLOR_BY), ColorOption.NONE) :
                    colorOption;
        }

        public String getColorByTag() {
            return colorByTag == null ? getPreferences().get(SAM_COLOR_BY_TAG) : colorByTag;
        }

        public ShadeAlignmentsOption getShadeAlignmentsOption() {
            if (shadeAlignmentsOption != null) {
                return shadeAlignmentsOption;
            } else {
                try {
                    return ShadeAlignmentsOption.valueOf(getPreferences().get(SAM_SHADE_ALIGNMENT_BY));
                } catch (IllegalArgumentException e) {
                    log.error("Error parsing alignment shade option: " + ShadeAlignmentsOption.valueOf(getPreferences().get(SAM_SHADE_ALIGNMENT_BY)));
                    return ShadeAlignmentsOption.NONE;
                }
            }
        }

        public int getMappingQualityLow() {
            return mappingQualityLow == null ? getPreferences().getAsInt(SAM_SHADE_QUALITY_LOW) : mappingQualityLow;
        }

        public int getMappingQualityHigh() {
            return mappingQualityHigh == null ? getPreferences().getAsInt(SAM_SHADE_QUALITY_HIGH) : mappingQualityHigh;
        }

        SortOption getSortOption() {
            return sortOption == null ? CollUtils.valueOf(SortOption.class, getPreferences().get(SAM_SORT_OPTION), null) : sortOption;
        }

        String getSortByTag() {
            return sortByTag == null ? getPreferences().get(SAM_SORT_BY_TAG) : sortByTag;
        }

        public String getGroupByTag() {
            return groupByTag == null ? getPreferences().get(SAM_GROUP_BY_TAG) : groupByTag;
        }

        public Range getGroupByPos() {
            if (groupByPos == null) {
                String pos = getPreferences().get(SAM_GROUP_BY_POS);
                if (pos != null) {
                    String[] posParts = pos.split(" ");
                    if (posParts.length != 2) {
                        groupByPos = null;
                    } else {
                        int posChromStart = Integer.parseInt(posParts[1]);
                        groupByPos = new Range(posParts[0], posChromStart, posChromStart + 1);
                    }
                }
            }
            return groupByPos;
        }

        public boolean isInvertSorting() {
            return invertSorting == null ? getPreferences().getAsBoolean(SAM_INVERT_SORT) : invertSorting;
        }

        public boolean isInvertGroupSorting() {
            return invertGroupSorting;
        }

        public String getLinkByTag() {
            return linkByTag;
        }

        public GroupOption getGroupByOption() {
            GroupOption gbo = groupByOption;
            // Interpret null as the default option.
            gbo = (gbo == null) ?
                    CollUtils.valueOf(GroupOption.class, getPreferences().get(SAM_GROUP_OPTION), GroupOption.NONE) :
                    gbo;
            // Add a second check for null in case defaultValues.groupByOption == null
            gbo = (gbo == null) ? GroupOption.NONE : gbo;

            return gbo;
        }

        public boolean isLinkedReads() {
            return linkedReads != null && linkedReads;
        }

        public boolean isQuickConsensusMode() {
            return quickConsensusMode == null ? getPreferences().getAsBoolean(SAM_QUICK_CONSENSUS_MODE) : quickConsensusMode;
        }

        public boolean isHideSmallIndels() {
            return hideSmallIndels == null ? getPreferences().getAsBoolean(SAM_HIDE_SMALL_INDEL) : hideSmallIndels;
        }

        public int getSmallIndelThreshold() {
            return smallIndelThreshold == null ? getPreferences().getAsInt(SAM_SMALL_INDEL_BP_THRESHOLD) : smallIndelThreshold;
        }


        @Override
        public void marshalXML(Document document, Element element) {

            if (shadeBasesOption != null) {
                element.setAttribute("shadeBasesOption", shadeBasesOption.toString());
            }
            if (shadeCenters != null) {
                element.setAttribute("shadeCenters", shadeCenters.toString());
            }
            if (flagUnmappedPairs != null) {
                element.setAttribute("flagUnmappedPairs", flagUnmappedPairs.toString());
            }
            if (showAllBases != null) {
                element.setAttribute("showAllBases", showAllBases.toString());
            }
            if (minInsertSize != null) {
                element.setAttribute("minInsertSize", minInsertSize.toString());
            }
            if (maxInsertSize != null) {
                element.setAttribute("maxInsertSize", maxInsertSize.toString());
            }
            if (colorOption != null) {
                element.setAttribute("colorOption", colorOption.toString());
            }
            if (groupByOption != null) {
                element.setAttribute("groupByOption", groupByOption.toString());
            }
            if (shadeAlignmentsOption != null) {
                element.setAttribute("shadeAlignmentsByOption", shadeAlignmentsOption.toString());
            }
            if (mappingQualityLow != null) {
                element.setAttribute("mappingQualityLow", mappingQualityLow.toString());
            }
            if (mappingQualityHigh != null) {
                element.setAttribute("mappingQualityHigh", mappingQualityHigh.toString());
            }
            if (viewPairs != false) {
                element.setAttribute("viewPairs", Boolean.toString(viewPairs));
            }
            if (colorByTag != null) {
                element.setAttribute("colorByTag", colorByTag);
            }
            if (groupByTag != null) {
                element.setAttribute("groupByTag", groupByTag);
            }
            if (sortByTag != null) {
                element.setAttribute("sortByTag", sortByTag);
            }
            if (linkByTag != null) {
                element.setAttribute("linkByTag", linkByTag);
            }
            if (linkedReads != null) {
                element.setAttribute("linkedReads", linkedReads.toString());
            }
            if (quickConsensusMode != null) {
                element.setAttribute("quickConsensusMode", quickConsensusMode.toString());
            }
            if (showMismatches != null) {
                element.setAttribute("showMismatches", showMismatches.toString());
            }
            if (computeIsizes != null) {
                element.setAttribute("computeIsizes", computeIsizes.toString());
            }
            if (minInsertSizePercentile != null) {
                element.setAttribute("minInsertSizePercentile", minInsertSizePercentile.toString());
            }
            if (maxInsertSizePercentile != null) {
                element.setAttribute("maxInsertSizePercentile", maxInsertSizePercentile.toString());
            }
            if (pairedArcView != null) {
                element.setAttribute("pairedArcView", pairedArcView.toString());
            }
            if (flagZeroQualityAlignments != null) {
                element.setAttribute("flagZeroQualityAlignments", flagZeroQualityAlignments.toString());
            }
            if (groupByPos != null) {
                element.setAttribute("groupByPos", groupByPos.toString());
            }
            if (invertSorting != null) {
                element.setAttribute("invertSorting", Boolean.toString(invertSorting));
            }
            if (sortOption != null) {
                element.setAttribute("sortOption", sortOption.toString());
            }
            if (invertGroupSorting) {
                element.setAttribute("invertGroupSorting", Boolean.toString(invertGroupSorting));
            }
            if (hideSmallIndels != null) {
                element.setAttribute("hideSmallIndels", hideSmallIndels.toString());
            }
            if (smallIndelThreshold != null) {
                element.setAttribute("smallIndelThreshold", smallIndelThreshold.toString());
            }
            if (showInsertionMarkers != null) {
                element.setAttribute("showInsertionMarkers", showInsertionMarkers.toString());
            }
        }


        @Override
        public void unmarshalXML(Element element, Integer version) {
            if (element.hasAttribute("shadeBasesOption")) {
                String v = element.getAttribute("shadeBasesOption");
                if (v != null) {
                    shadeBasesOption = v.equalsIgnoreCase("quality") || v.equalsIgnoreCase("true");
                }
            }
            if (element.hasAttribute("shadeCenters")) {
                shadeCenters = Boolean.parseBoolean(element.getAttribute("shadeCenters"));
            }
            if (element.hasAttribute("showAllBases")) {
                showAllBases = Boolean.parseBoolean(element.getAttribute("showAllBases"));
            }
            if (element.hasAttribute("flagUnmappedPairs")) {
                flagUnmappedPairs = Boolean.parseBoolean(element.getAttribute("flagUnmappedPairs"));
            }

            if (element.hasAttribute("minInsertSize")) {
                minInsertSize = Integer.parseInt(element.getAttribute("minInsertSize"));
            }
            if (element.hasAttribute("maxInsertSize")) {
                maxInsertSize = Integer.parseInt(element.getAttribute("maxInsertSize"));
            }
            if (element.hasAttribute("colorOption")) {
                colorOption = ColorOption.valueOf(element.getAttribute("colorOption"));
            }
            if (element.hasAttribute("sortOption")) {
                sortOption = SortOption.valueOf((element.getAttribute("sortOption")));
            }
            if (element.hasAttribute("groupByOption")) {
                groupByOption = GroupOption.valueOf(element.getAttribute("groupByOption"));
            }
            if (element.hasAttribute("shadeAlignmentsByOption")) {
                shadeAlignmentsOption = ShadeAlignmentsOption.valueOf(element.getAttribute("shadeAlignmentsByOption"));
            }
            if (element.hasAttribute("mappingQualityLow")) {
                mappingQualityLow = Integer.parseInt(element.getAttribute("mappingQualityLow"));
            }
            if (element.hasAttribute("mappingQualityHigh")) {
                mappingQualityHigh = Integer.parseInt(element.getAttribute("mappingQualityHigh"));
            }
            if (element.hasAttribute("viewPairs")) {
                viewPairs = Boolean.parseBoolean(element.getAttribute("viewPairs"));
            }
            if (element.hasAttribute("colorByTag")) {
                colorByTag = element.getAttribute("colorByTag");
            }
            if (element.hasAttribute("groupByTag")) {
                groupByTag = element.getAttribute("groupByTag");
            }
            if (element.hasAttribute("sortByTag")) {
                sortByTag = element.getAttribute("sortByTag");
            }
            if (element.hasAttribute("linkByTag")) {
                linkByTag = element.getAttribute("linkByTag");
            }
            if (element.hasAttribute("linkedReads")) {
                linkedReads = Boolean.parseBoolean(element.getAttribute("linkedReads"));
            }
            if (element.hasAttribute("quickConsensusMode")) {
                quickConsensusMode = Boolean.parseBoolean(element.getAttribute("quickConsensusMode"));
            }
            if (element.hasAttribute("showMismatches")) {
                showMismatches = Boolean.parseBoolean(element.getAttribute("showMismatches"));
            }
            if (element.hasAttribute("computeIsizes")) {
                computeIsizes = Boolean.parseBoolean(element.getAttribute("computeIsizes"));
            }
            if (element.hasAttribute("minInsertSizePercentile")) {
                minInsertSizePercentile = Double.parseDouble(element.getAttribute("minInsertSizePercentile"));
            }
            if (element.hasAttribute("maxInsertSizePercentile")) {
                maxInsertSizePercentile = Double.parseDouble(element.getAttribute("maxInsertSizePercentile"));
            }
            if (element.hasAttribute("pairedArcView")) {
                pairedArcView = Boolean.parseBoolean(element.getAttribute("pairedArcView"));
            }
            if (element.hasAttribute("flagZeroQualityAlignments")) {
                flagZeroQualityAlignments = Boolean.parseBoolean(element.getAttribute("flagZeroQualityAlignments"));
            }
            if (element.hasAttribute("groupByPos")) {
                groupByPos = Range.fromString(element.getAttribute("groupByPos"));
            }
            if (element.hasAttribute("invertSorting")) {
                invertSorting = Boolean.parseBoolean(element.getAttribute("invertSorting"));
            }
            if (element.hasAttribute("invertGroupSorting")) {
                invertGroupSorting = Boolean.parseBoolean(element.getAttribute("invertGroupSorting"));
            }
            if (element.hasAttribute("hideSmallIndels")) {
                hideSmallIndels = Boolean.parseBoolean(element.getAttribute("hideSmallIndels"));
            }
            if (element.hasAttribute("smallIndelThreshold")) {
                smallIndelThreshold = Integer.parseInt(element.getAttribute("smallIndelThreshold"));
            }
            if (element.hasAttribute("showInsertionMarkers")) {
                showInsertionMarkers = Boolean.parseBoolean(element.getAttribute("showInsertionMarkers"));
            }
        }
    }


}
