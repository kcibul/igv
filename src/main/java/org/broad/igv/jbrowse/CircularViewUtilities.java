package org.broad.igv.jbrowse;

import htsjdk.tribble.Feature;
import org.broad.igv.bedpe.BedPEFeature;
import org.broad.igv.feature.Chromosome;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.sam.Alignment;
import org.broad.igv.sam.ReadMate;
import org.broad.igv.ui.color.ColorUtilities;
import org.broad.igv.util.ChromosomeColors;
import org.broad.igv.variant.Variant;
import org.broad.igv.variant.vcf.MateVariant;

import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

public class CircularViewUtilities {

    public static boolean ping() {
        try {
            String response = SocketSender.send("{\"message\": \"ping\"}", true);
            return "OK".equals(response);
        } catch (Exception e) {
            return false;
        }
    }

    public static void sendBedpeToJBrowse(List<BedPEFeature> features, String trackName, Color color) {
        Chord[] chords = new Chord[features.size()];
        int index = 0;
        for (BedPEFeature f : features) {
            chords[index++] = new Chord(f);
        }
        sendChordsToJBrowse(chords, trackName, color, "0.5");
    }

    public static void sendAlignmentsToJBrowse(List<Alignment> alignments, String trackName, Color color) {

        Chord[] chords = new Chord[alignments.size()];
        int index = 0;
        for (Alignment a : alignments) {
            chords[index++] = new Chord(a);
        }
        sendChordsToJBrowse(chords, trackName, color, "0.02");
    }

    public static void sendVariantsToJBrowse(List<Feature> variants, String trackName, Color color) {

        Chord[] chords = new Chord[variants.size()];
        int index = 0;
        for (Feature f : variants) {
            if (f instanceof Variant) {
                Variant v = f instanceof MateVariant ? ((MateVariant) f).mate : (Variant) f;
                Map<String, Object> attrs = v.getAttributes();
                if (attrs.containsKey("CHR2") && attrs.containsKey("END")) {
                    chords[index++] = new Chord(v);
                }
            }
        }
        sendChordsToJBrowse(chords, trackName, color, "0.5");
    }


    public static void sendChordsToJBrowse(Chord[] chords, String trackName, Color color, String alpha) {

        // We can't know if an assembly has been set, or if it has its the correct one.
        changeGenome(GenomeManager.getInstance().getCurrentGenome());

        String colorString = "rgba(" + color.getRed() + "," + color.getGreen() + "," + color.getBlue() + "," + alpha + ")";
        CircViewTrack t = new CircViewTrack(chords, trackName, colorString);
        CircViewMessage message = new CircViewMessage("addChords", t);


        String json = message.toJson();
        System.out.println(json);
        SocketSender.send(json);
    }

    public static void changeGenome(Genome genome) {
        List<String> wgChrNames = genome.getLongChromosomeNames();
        CircViewRegion[] regions = new CircViewRegion[wgChrNames.size()];
        int idx = 0;
        for (String chr : wgChrNames) {
            Chromosome c = genome.getChromosome(chr);
            int length = c.getLength();
            Color color = ChromosomeColors.getColor(chr);
            String colorString = "rgb(" + ColorUtilities.colorToString(color) + ")";
            regions[idx++] = new CircViewRegion(chr, length, colorString);
        }
        CircViewAssembly assm = new CircViewAssembly(genome.getId(), genome.getDisplayName(), regions);
        CircViewMessage message = new CircViewMessage("setAssembly", assm);

        String json = message.toJson();
        System.out.println();
        SocketSender.send(json);

    }
}

class CircViewMessage {
    String message;
    CircViewAssembly assembly;
    CircViewTrack track;

    public CircViewMessage(String message, CircViewAssembly data) {
        this.message = message;
        this.assembly = data;
    }

    public CircViewMessage(String message, CircViewTrack data) {
        this.message = message;
        this.track = data;
    }

    public String toJson() {

        StringBuffer buf = new StringBuffer();
        buf.append("{");
        buf.append(JsonUtils.toJson("message", message));
        buf.append(",\"data\":");
        if(this.assembly != null) {
            buf.append(assembly.toJson());
        } else if(this.track != null) {
            buf.append(track.toJson());
        }
        buf.append("}");
        return buf.toString();
    }

}

class CircViewAssembly {
    String id;
    String name;
    CircViewRegion [] chromosomes;

    public CircViewAssembly(String id, String name, CircViewRegion[] regions) {
        this.id = id;
        this.name = name;
        this.chromosomes = regions;
    }

    public String toJson() {
        StringBuffer buf = new StringBuffer();
        buf.append("{");
        buf.append(JsonUtils.toJson("id", id));
        buf.append(",");
        buf.append(JsonUtils.toJson("name", name));
        buf.append(",\"chromosomes\":");
        buf.append("[");
        boolean first = true;
        for(CircViewRegion c : this.chromosomes) {
            if(!first) {
                buf.append(",");
            }
            buf.append(c.toJson());
            first = false;
        }
        buf.append("]");
        buf.append("}");
        return buf.toString();
    }
}

class CircViewRegion {
    String name;
    int bpLength;
    String color;
    public CircViewRegion(String name, int bpLength, String color) {
        this.name = name;
        this.bpLength = bpLength;
        this.color = color;
    }

    public String toJson() {
        StringBuffer buf = new StringBuffer();
        buf.append("{");
        buf.append(JsonUtils.toJson("name", name));
        buf.append(",");
        buf.append(JsonUtils.toJson("color", color));
        buf.append(",");
        buf.append(JsonUtils.toJson("bpLength", bpLength));
        buf.append("}");
        return buf.toString();
    }
}

class CircViewTrack {
    String name;
    String color;
    Chord[] chords;

    public CircViewTrack(Chord[] chords, String name, String color) {
        this.name = name;
        this.color = color;
        this.chords = chords;
    }

    public String toJson() {
        StringBuffer buf = new StringBuffer();
        buf.append("{");
        buf.append(JsonUtils.toJson("name", name));
        buf.append(",");
        buf.append(JsonUtils.toJson("color", color));
        buf.append(",\"chords\":");
        buf.append("[");
        boolean first = true;
        for(Chord c : chords) {
            if(!first) {
                buf.append(",");
            }
            buf.append(c.toJson());
            first = false;
        }
        buf.append("]");
        buf.append("}");
        return buf.toString();
    }
}

class Mate {
    String refName;
    int start;
    int end;

    public Mate(String refName, int start, int end) {
        this.refName = refName;
        this.start = start;
        this.end = end;
    }
    public String toJson() {
        StringBuffer buf = new StringBuffer();
        buf.append("{");
        buf.append(JsonUtils.toJson("refName", refName));
        buf.append(",");
        buf.append(JsonUtils.toJson("start", start));
        buf.append(",");
        buf.append(JsonUtils.toJson("end", end));
        buf.append("}");
        return buf.toString();
    }
}

class Chord {
    String uniqueId;
    String color;
    String refName;
    int start;
    int end;
    Mate mate;

    public Chord(BedPEFeature f) {
        this.uniqueId = f.chr1 + ":" + f.start1 + "-" + f.end1 + "_" + f.chr2 + ":" + f.start2 + "-" + f.end2;
        this.refName = f.chr1.startsWith("chr") ? f.chr1.substring(3) : f.chr1;
        this.start = f.start1;
        this.end = f.end1;
        this.mate = new Mate(f.chr2.startsWith("chr") ? f.chr2.substring(3) : f.chr2, f.start2, f.end2);
        this.color = "rgba(0, 0, 255, 0.1)";
    }

    public Chord(Alignment a) {
        ReadMate mate = a.getMate();
        this.uniqueId = a.getReadName();
        this.refName = a.getChr().startsWith("chr") ? a.getChr().substring(3) : a.getChr();
        this.start = a.getStart();
        this.end = a.getEnd();
        this.mate = new Mate(mate.getChr().startsWith("chr") ? mate.getChr().substring(3) : mate.getChr(),
                mate.getStart(), mate.getStart() + 1);
        this.color = "rgba(0, 0, 255, 0.02)";
    }

    public Chord(Variant v) {

        Map<String, Object> attrs = v.getAttributes();
        String chr2 = shortName(attrs.get("CHR2").toString());
        int end2 = Integer.parseInt(attrs.get("END").toString());
        int start2 = end2 - 1;
        String chr1 = shortName(v.getChr());
        int start1 = v.getStart();
        int end1 = v.getEnd();

        this.uniqueId = chr1 + "_" + start1 + ":" + end1 + "-" + chr2 + "_" + start2 + ":" + end2;
        this.refName = chr1;
        this.start = start1;
        this.end = end1;
        this.mate = new Mate(chr2, start2, end2);
        this.color = "rgb(0,0,255)";
    }

    public String toJson() {
        StringBuffer buf = new StringBuffer();
        buf.append("{");
        buf.append(JsonUtils.toJson("uniqueId", uniqueId));
        buf.append(",");
        buf.append(JsonUtils.toJson("color", color));
        buf.append(",");
        buf.append(JsonUtils.toJson("refName", refName));
        buf.append(",");
        buf.append(JsonUtils.toJson("start", start));
        buf.append(",");
        buf.append(JsonUtils.toJson("end", end));
        buf.append(",");
        buf.append("\"mate\":");
        buf.append(mate.toJson());
        buf.append("}");
        return buf.toString();
    }

    static String shortName(String chr) {
        return chr.startsWith("chr") ? chr.substring(3) : chr;
    }

}

class JsonUtils {

    public static String toJson(String name, String value) {
        return "\"" + name + "\": \"" + value + "\"";
    }

    public static String toJson(String name, int value) {
        return "\"" + name + "\": " + value;
    }
}



class SocketSender {

    static String send(String json) {
        return send(json, false);
    }

    static String send(String json, boolean suppressErrors) {
        Socket socket = null;
        PrintWriter out = null;
        BufferedReader in = null;
        try {
            socket = new Socket("127.0.0.1", 1234);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println(json);
            out.flush();
            String response = in.readLine();
            return response;
        } catch (UnknownHostException e) {
            String err = "Unknown host exception: " + e.getMessage();
            if (!suppressErrors) System.err.println(err);
            return err;

        } catch (IOException e) {
            String message = "IO Exception: " + e.getMessage();
            if (!suppressErrors) System.err.println(message);
            return message;
        } finally {
            try {
                in.close();
                out.close();
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}




/*


const MINIMUM_SV_LENGTH = 1000000;

        const circViewIsInstalled = () => CircularView.isInstalled();

        const shortChrName = (chrName) => {
        return chrName.startsWith("chr") ? chrName.substring(3) : chrName;
        }

        const makePairedAlignmentChords = (alignments, color) => {
        color = color || 'rgba(0, 0, 255, 0.02)'
        const chords = [];
        for (let a of alignments) {
        const mate = a.mate;
        if (mate && mate.chr && mate.position) {
        chords.push({
        uniqueId: a.readName,
        refName: shortChrName(a.chr),
        start: a.start,
        end: a.end,
        mate: {
        refName: shortChrName(mate.chr),
        start: mate.position - 1,
        end: mate.position,
        },
        color: color
        });
        }
        }
        return chords;
        }

        const makeBedPEChords = (features, color) => {

        color = color || 'rgb(0,0,255)';

        return features.map(v => {

        // If v is a whole-genome feature, get the true underlying variant.
        const f = v._f || v;

        return {
        uniqueId: `${f.chr1}:${f.start1}-${f.end1}_${f.chr2}:${f.start2}-${f.end2}`,
        refName: shortChrName(f.chr1),
        start: f.start1,
        end: f.end1,
        mate: {
        refName: shortChrName(f.chr2),
        start: f.start2,
        end: f.end2,
        },
        color: color,
        igvtype: 'bedpe'
        }
        })
        }


        const makeVCFChords = (features, color) => {

        color = color || 'rgb(0,0,255)';

        const svFeatures = features.filter(v => {
        const f = v._f || v;
        const isLargeEnough = f.info.CHR2 && f.info.END &&
        (f.info.CHR2 !== f.chr || Math.abs(Number.parseInt(f.info.END) - f.pos) > MINIMUM_SV_LENGTH);
        return isLargeEnough;
        });
        return svFeatures.map(v => {

        // If v is a whole-genome feature, get the true underlying variant.
        const f = v._f || v;

        const pos2 = Number.parseInt(f.info.END);
        const start2 = pos2 - 100;
        const end2 = pos2 + 100;

        return {
        uniqueId: `${f.chr}:${f.start}-${f.end}_${f.info.CHR2}:${f.info.END}`,
        refName: shortChrName(f.chr),
        start: f.start,
        end: f.end,
        mate: {
        refName: shortChrName(f.info.CHR2),
        start: start2,
        end: end2
        },
        color: color,
        igvtype: 'vcf'
        }
        })
        }

        const makeCircViewChromosomes = (genome) => {
        const regions = [];
        const colors = [];
        for (let chrName of genome.wgChromosomeNames) {
        const chr = genome.getChromosome(chrName);
        colors.push(getChrColor(chr.name));
        regions.push(
        {
        name: chr.name,
        bpLength: chr.bpLength
        }
        )
        }
        return regions;
        }


        function createCircularView(el, browser) {

        const circularView = new CircularView(el, {

        assembly: {
        name: browser.genome.id,
        id: browser.genome.id,
        chromosomes: makeCircViewChromosomes(browser.genome)
        },

        onChordClick: (feature, chordTrack, pluginManager) => {

        const f1 = feature.data;
        const f2 = f1.mate;
        const flanking = 2000;

        const l1 = new Locus({chr: browser.genome.getChromosomeName(f1.refName), start: f1.start, end: f1.end});
        const l2 = new Locus({chr: browser.genome.getChromosomeName(f2.refName), start: f2.start, end: f2.end});

        let loci;
        if ("alignment" === f1.igvtype) {   // append
        loci = this.currentLoci().map(str => Locus.fromLocusString(str));
        for (let l of [l1, l2]) {
        if (!loci.some(locus => {
        return locus.contains(l)
        })) {
        // add flanking
        l.start = Math.max(0, l.start - flanking);
        l.end += flanking;
        loci.push(l)
        }
        }
        } else {
        l1.start = Math.max(0, l1.start - flanking);
        l1.end += flanking;
        l2.start = Math.max(0, l2.start - flanking);
        l2.end += flanking;
        loci = [l1, l2];
        }

        const searchString = loci.map(l => l.getLocusString()).join(" ");
        browser.search(searchString);
        }
        });
        browser.circularView = circularView;
        circularView.hide();
        return circularView;
        }

        export {circViewIsInstalled, makeBedPEChords, makePairedAlignmentChords, makeVCFChords, createCircularView}


        */