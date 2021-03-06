package domainannotation;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import java.net.URL;
import java.text.SimpleDateFormat;

import us.kbase.auth.AuthService;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.*;
import us.kbase.workspace.*;
import us.kbase.shock.client.*;
import us.kbase.kbasegenomes.*;
import us.kbase.kbasereport.*;
import us.kbase.kbasecollections.*;
import us.kbase.common.utils.FastaWriter;

import us.kbase.common.service.Tuple11;
import us.kbase.common.service.Tuple2;
import us.kbase.common.service.Tuple4;
import us.kbase.common.service.Tuple5;
import us.kbase.common.utils.AlignUtil;
import us.kbase.common.utils.CorrectProcess;
import us.kbase.common.utils.RpsBlastParser;
import us.kbase.common.taskqueue.TaskQueueConfig;
import us.kbase.kbasegenomes.Feature;
import us.kbase.kbasegenomes.Genome;
import us.kbase.workspace.ObjectData;
import us.kbase.workspace.ObjectIdentity;

import com.fasterxml.jackson.databind.*;

import org.strbio.IO;
import org.strbio.io.*;
import org.strbio.util.*;

import static java.lang.ProcessBuilder.Redirect;

/**
   This class runs a domain search against a single genome, using
   RPS-BLAST and HMMER.  Domain hits are saved in DomainAnnotation
   workspace objects.
*/
public class DomainAnnotationImpl {
    private static String MAX_BLAST_EVALUE = "1e-04";
    
    public static final String domainAnnotationWsType = "KBaseGeneFamilies.DomainAnnotation";
    public static final String domainAlignmentsWsType = "KBaseGeneFamilies.DomainAlignments";

    protected static File tempDir = new File("/kb/module/work/");
    
    /**
       creates a workspace client; if token is null, client can
       only read public workspaces.
    */
    public static WorkspaceClient createWsClient(String wsURL,
                                                 AuthToken token) throws Exception {
        WorkspaceClient rv = null;

        if (token==null)
            rv = new WorkspaceClient(new URL(wsURL));
        else
            rv = new WorkspaceClient(new URL(wsURL),token);
        rv.setAuthAllowedForHttp(true);
        return rv;
    }

    /**
       helper function to get the reference back when saving an object
    */
    public static String getRefFromObjectInfo(Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String,String>> info) {
        return info.getE7() + "/" + info.getE1() + "/" + info.getE5();
    }
    
    /**
       store a file in Shock; returns handle.
       If file doesn't exist or can't be read, returns null.
    */
    public static Handle toShock(String shockURL,
                                 AuthToken token,
                                 File f) throws Exception {
        if (!f.canRead())
            return null;
        
        Handle rv = new Handle()
            .withFileName(f.getName());
        
        BasicShockClient shockClient = new BasicShockClient(new URL(shockURL), token);
        InputStream is = new BufferedInputStream(new FileInputStream(f));
        ShockNode sn = shockClient.addNode(is,f.getName(),null);
        is.close();
        String shockNodeID = sn.getId().getId();
        rv.setShockId(shockNodeID);
        return rv;
    }

    /**
       Load a file from Shock; returns File, or null if file couldn't be read.
       If the file from Shock is 0-length, it is deleted and null is returned.
    */
    public static File fromShock(Handle h,
                                 String shockUrl,
                                 AuthToken token,
                                 File f,
                                 boolean gzip) throws Exception {

        System.err.println("shock cmd equivalent to "+"/usr/bin/curl -k -X GET "+shockUrl+" -H \"Authorization: OAuth "+token.toString()+"\""+(gzip ? "| /bin/gzip" : ""));
        
        BasicShockClient shockClient = new BasicShockClient(new URL(shockUrl), token);
        ShockNode sn = shockClient.getNode(new ShockNodeId(h.getShockId()));
        OutputStream os = new FileOutputStream(f);
        if (gzip)
            os = new GZIPOutputStream(os);

        shockClient.getFile(sn,os);

        if (f.length()==0)
            f.delete();

        if (!f.canRead())
            return null;
        
        return f;
    }

    /**
       Make a provenance object
    */
    public static List<ProvenanceAction> makeProvenance(String description,
                                                        String methodName,
                                                        List<UObject> methodParams) throws Exception {
        // to get service version:
        DomainAnnotationServer server = new DomainAnnotationServer();
        
        return new ArrayList<ProvenanceAction>
            (Arrays.asList(new ProvenanceAction()
                           .withDescription(description)
                           .withService("DomainAnnotation")
                           .withServiceVer(server.version(null))
                           .withMethod(methodName)
                           .withMethodParams(methodParams)));
    }

    /**
       Make and save Report object, returning its name and reference
    */
    public static String[] makeReport(WorkspaceClient wc,
                                      String ws,
                                      String reportText,
                                      List<String> warnings,
                                      List<WorkspaceObject> objects,
                                      List<ProvenanceAction> provenance) throws Exception {
        String reportName = "domainannotation_report_"+UUID.randomUUID().toString();
        Report report = new Report()
            .withTextMessage(reportText)
            .withWarnings(warnings)
            .withObjectsCreated(objects);

        ObjectSaveData reportData = new ObjectSaveData()
            .withType("KBaseReport.Report")
            .withData(new UObject(report))
            .withName(reportName)
            .withHidden(1L)
            .withProvenance(provenance);
        String reportRef = getRefFromObjectInfo(wc.saveObjects(new SaveObjectsParams().withWorkspace(ws).withObjects(Arrays.asList(reportData))).get(0));
        return new String[] { reportName, reportRef };
    }

    /**
       save mapped reads to workspace, with provenance.
       returns ref
    */
    public static String saveDomainAnnotation(WorkspaceClient wc,
                                              String ws,
                                              String id,
                                              DomainAnnotation da,
                                              List<ProvenanceAction> provenance) throws Exception {
        ObjectSaveData data = new ObjectSaveData()
            .withType(domainAnnotationWsType)
            .withMeta(getMetadata(da))
            .withProvenance(provenance)
            .withData(new UObject(da));
        try {
            long objid = Long.parseLong(id);
            data.withObjid(objid);
        } catch (NumberFormatException ex) {
            data.withName(id);
        }
        return getRefFromObjectInfo(wc.saveObjects(new SaveObjectsParams().withWorkspace(ws).withObjects(Arrays.asList(data))).get(0));
    }

    /**
       Runs a domain search on a single genome, returning annotations.
       Takes a domainModelSetRef (in the SearchDomainsInput) as input,
       which is searched as individual libraries.
    */
    public static SearchDomainsOutput run(String wsURL,
                                          String shockURL,
                                          AuthToken token,
                                          SearchDomainsInput input) throws Exception {

        WorkspaceClient wc = createWsClient(wsURL,token);

        // turn local into absolute paths
        String genomeRef = input.getGenomeRef();
        if (genomeRef.indexOf("/") == -1)
            genomeRef = input.getWs()+"/"+genomeRef;
        String domainModelSetRef = input.getDmsRef();
        if (domainModelSetRef.indexOf("/") == -1)
            domainModelSetRef = input.getWs()+"/"+domainModelSetRef;
        
        // for provenance
        String methodName = "DomainAnnotation.search_domains";
        List<UObject> methodParams = Arrays.asList(new UObject(input));
        
        // start building report
        String reportText = "Search Domains output:\n";
        List<String> warnings = null;
        List<WorkspaceObject> objects = new ArrayList<WorkspaceObject>();

        // run annotation
        DomainAnnotation da = null;
        String domainAnnotationRef = null;
        try {
            reportText += "Getting DomainModelSet from storage.\n";
            final DomainModelSet dms = wc.getObjects(Arrays.asList(new ObjectIdentity().withRef(domainModelSetRef))).get(0).getData().asClassInstance(DomainModelSet.class);
            reportText += "Getting Genome from storage.\n";
            final Genome genome = wc.getObjects(Arrays.asList(new ObjectIdentity().withRef(genomeRef))).get(0).getData().asClassInstance(Genome.class);
            Map<String,String> domainLibMap = dms.getDomainLibs();

            // collect one set of annotations per library
            for (String id : domainLibMap.values()) {
                reportText += "Running domain search against library "+id;
                DomainLibrary dl = wc.getObjects(Arrays.asList(new ObjectIdentity().withRef(id))).get(0).getData().asClassInstance(DomainLibrary.class);
                DomainAnnotation results = runDomainSearch(genome, genomeRef, domainModelSetRef, dl, shockURL, token);

                // combine all the results into one object
                if (da==null)
                    da = results;
                else
                    combineData(results,da);
            }

            // save final DomainAnnotation object
            domainAnnotationRef = saveDomainAnnotation(wc,
                                                       input.getWs(),
                                                       input.getOutputResultId(),
                                                       da,
                                                       makeProvenance("Domain Annotation",
                                                                      methodName,
                                                                      methodParams));
            objects.add(new WorkspaceObject()
                        .withRef(domainAnnotationRef)
                        .withDescription("Domain Annotations"));
        }
        catch (Exception e) {
            reportText += "\n\nERROR: "+e.getMessage();
            warnings = new ArrayList<String>();
            warnings.add("ERROR: "+e.getMessage());
        }

        // generate report with list of objects created
        String[] report = makeReport(wc,
                                     input.getWs(),
                                     reportText,
                                     warnings,
                                     objects,
                                     makeProvenance("Domain Annotation Report",
                                                    methodName,
                                                    methodParams));

        SearchDomainsOutput rv = new SearchDomainsOutput()
            .withOutputResultId(domainAnnotationRef)
            .withReportName(report[0])
            .withReportRef(report[1]);

        return rv;
    }

    /**
       Runs a domain search on a single genome, returning annotations.
       This works on a single library, but needs metadata (references
       to Genome and DomainModelSet) to populate the annotation object.
    */
    public static DomainAnnotation runDomainSearch(Genome genome,
                                                   String genomeRef,
                                                   String domainModelSetRef,
                                                   DomainLibrary dl,
                                                   String shockURL,
                                                   AuthToken token) throws Exception {
        String genomeName = genome.getScientificName();
        File dbFile = new File(getDomainsDir().getPath()+"/"+dl.getLibraryFiles().get(0).getFileName());
        File fastaFile = File.createTempFile("proteome", ".fasta", tempDir);
        File outFile = null;

        final Map<String,Long> modelNameToLength = new HashMap<String,Long>();

        // save the length of each model, to compute coverage.
        // This replaces modelNameToRefConsensus in Roman's legacy code:
        Map<String,DomainModel> libDomains = dl.getDomains();
        for (String accession : libDomains.keySet()) {
            DomainModel m = libDomains.get(accession);
            modelNameToLength.put(accession, m.getLength());
        }

        // make sure we have local copies of all library files
        prepareLibraryFiles(dl,shockURL,token);

        try {
            final Map<String, List<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>>> contig2prots =
                new TreeMap<String, List<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>>>();
            FastaWriter fw = new FastaWriter(fastaFile);
            int protCount = 0;
            final Map<Integer, Tuple2<String, Long>> posToContigFeatIndex = new LinkedHashMap<Integer, Tuple2<String, Long>>();
            Map<String, Tuple2<String, Long>> featIdToContigFeatIndex = new TreeMap<String, Tuple2<String, Long>>();
            // to work around genomes with missing contigs:
            HashSet<String> realContigs = new HashSet<String>();
            // write out each protein sequentially into a FASTA file,
            // keeping track of its (first) position in the genome
            try {
                List<Feature> features = genome.getFeatures();
                int pos = -1;
                for (Feature feat : features) {
                    pos++;
                    String seq = feat.getProteinTranslation();
                    if (feat.getLocation().size() < 1)
                        continue;
                    Tuple4<String, Long, String, Long> loc = feat.getLocation().get(0);
                    String contigId = loc.getE1();
                    String featId = feat.getId();
                    if ((contigId==null) || (featId==null))
                        continue;
                    if (seq != null && !seq.isEmpty()) {
                        fw.write("" + pos, seq);
                        Tuple2<String, Long> contigFeatIndex = new Tuple2<String, Long>().withE1(contigId);
                        posToContigFeatIndex.put(pos, contigFeatIndex);
                        featIdToContigFeatIndex.put(featId, contigFeatIndex);
                        protCount++;
                        realContigs.add(contigId);
                    }
                    List<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>> prots = contig2prots.get(contigId);
                    if (prots == null) {
                        prots = new ArrayList<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>>();
                        contig2prots.put(contigId, prots);
                    }
                    long start = loc.getE3().equals("-") ? (loc.getE2() - loc.getE4() + 1) : loc.getE2();
                    // fake the stop site based on protein length
                    long stop;
                    if (seq != null)
                        stop = start - 1 + ((seq.length()+1) * 3);
                    else {
                        // correct calculation for end of 1st exon:
                        stop = loc.getE3().equals("-") ? loc.getE2() : (loc.getE2() + loc.getE4() - 1);
                    }
                    long dir = loc.getE3().equals("-") ? -1 : +1;
                    prots.add(new Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>()
                              .withE1(feat.getId())
                              .withE2(start)
                              .withE3(stop)
                              .withE4(dir)
                              .withE5(new TreeMap<String, List<Tuple5<Long, Long, Double, Double, Double>>>()));
                }
            }
            finally {
                try { fw.close(); } catch (Exception ignore) {}
            }
            if (protCount == 0)
                throw new IllegalStateException("There are no protein translations in genome " + genomeName + " (" + genomeRef + ")");

            // make contig-based indices
            HashMap<String,Long> contigLengths = new HashMap<String,Long>();

            // first, get the reported contigs from genome object
            List<String> genomeContigs = genome.getContigIds();
            List<Long> genomeContigLengths = genome.getContigLengths();
            int nContigs = 0;
            if (genomeContigs != null)
                nContigs = genomeContigs.size();
            for (int contigPos = 0; contigPos < nContigs; contigPos++) {
                String contigId = genomeContigs.get(contigPos);
                if (!contig2prots.containsKey(contigId))
                    continue;
                long contigLength = 1;
                if ((genomeContigLengths != null) &&
                    (genomeContigLengths.size() > contigPos))
                    contigLength = genomeContigLengths.get(contigPos).longValue();
                contigLengths.put(contigId, new Long(contigLength));
            }
            // next, add any missing contigs as length 1
            for (String contigId : realContigs) {
                if (contigLengths.get(contigId) == null)
                    contigLengths.put(contigId, new Long(1));
            }

            // map contigs to "size" (both length and # of proteins)
            Map<String, Tuple2<Long, Long>> contigSizes = new TreeMap<String, Tuple2<Long, Long>>();
            for (String contigId : contigLengths.keySet()) {
                List<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>> prots = contig2prots.get(contigId);
                Collections.sort(prots, new Comparator<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>>() {
                        @Override
                        public int compare(Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>> o1,
                                           Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>> o2) {
                            return Long.compare(o1.getE2(), o2.getE2());
                        }
                    });
                long contigLength = contigLengths.get(contigId).longValue();
                contigSizes.put(contigId, new Tuple2<Long, Long>().withE1(contigLength).withE2((long)prots.size()));
                for (int i=0; i<prots.size(); i++) {
                    String featId = prots.get(i).getE1();
                    Tuple2<String, Long> contigFeatIndex = featIdToContigFeatIndex.get(featId);
                    if (contigFeatIndex != null)
                        contigFeatIndex.setE2((long)i);
                }
            }
            
            // run the appropriate annotation program
            String program = dl.getProgram();

            if (program.equals("rpsblast-2.2.30")) {
                outFile = runRpsBlast(dbFile, fastaFile);
                RpsBlastParser.processRpsOutput(outFile, new RpsBlastParser.RpsBlastCallback() {
                    @Override
                    public void next(String query,
                                     String subject,
                                     int qstart,
                                     String qseq,
                                     int sstart,
                                     String sseq,
                                     String evalue,
                                     double bitscore,
                                     double ident) throws Exception {
                        Long modelLength = modelNameToLength.get(subject);
                        if (modelLength == null)
                            throw new IllegalStateException("Unexpected subject name in prs blast result: " + subject);
                        int featurePos = Integer.parseInt(query);
                        String alignedSeq = AlignUtil.removeGapsFromSubject((int)(modelLength.longValue()), qseq, sstart - 1, sseq);
                        int coverage = 100 - AlignUtil.getGapPercent(alignedSeq);
                        Tuple2<String, Long> contigIdFeatIndex = posToContigFeatIndex.get(featurePos);
                        long featureIndex = contigIdFeatIndex.getE2();
                        Map<String, List<Tuple5<Long, Long, Double, Double, Double>>> domains = contig2prots.get(contigIdFeatIndex.getE1()).get((int)featureIndex).getE5();
                        List<Tuple5<Long, Long, Double, Double, Double>> places = domains.get(subject);
                        if (places == null) {
                            places = new ArrayList<Tuple5<Long, Long, Double, Double, Double>>();
                            domains.put(subject, places);
                        }
                        int qlen = AlignUtil.removeGaps(qseq).length();
                        places.add(new Tuple5<Long, Long, Double, Double, Double>()
                                   .withE1((long)qstart)
                                   .withE2((long)qstart + qlen - 1)
                                   .withE3(Double.parseDouble(evalue))
                                   .withE4(bitscore)
                                   .withE5(coverage / 100.0));
                    }
                });
            }
            else if (program.equals("hmmscan-3.1b1")) {
                outFile = runHmmer(dbFile, fastaFile);
                BufferedReader infile = IO.openReader(outFile.getPath());
                if (infile==null)
                    throw new Exception("failed to open HMMER output");

                int featurePos = -1;
                while (infile.ready()) {
                    String buffer = infile.readLine();
                    if (buffer==null) {
                        infile.close();
                        break;
                    }
                    if (buffer.startsWith("Query:"))
                        featurePos = StringUtil.atoi(buffer,7);
                    else if (buffer.startsWith("Domain annotation for each model (and alignments):")) {
                        buffer = infile.readLine();

                        while (buffer.startsWith(">> ")) {
                            Long modelLength = null;
                            String modelName = null;
                            StringTokenizer st = new StringTokenizer(buffer);
                            try {
                                st.nextToken();
                                modelName = st.nextToken();

                                modelLength = modelNameToLength.get(modelName);
                                if (modelLength == null)
                                    throw new IllegalStateException("No recognized domain in HMMER output line '"+buffer+"'");
                            }
                            catch (NoSuchElementException e) {
                                throw new Exception("Format error in HMMER output line '"+buffer+"'");
                            }
                            buffer = infile.readLine();
                            buffer = infile.readLine();
                            buffer = infile.readLine();

                            if (buffer.startsWith(">> "))
                                continue;

                            while (buffer.length() > 0) {
                                st = new StringTokenizer(buffer.substring(7));
                                try {
                                    double score = StringUtil.atod(st.nextToken());
                                    st.nextToken(); // bias
                                    st.nextToken(); // c-evalue

                                    String eString = st.nextToken();  // i-evalue
                                    // these numbers are 1-offset, for
                                    // compatibility with RPS-BLAST parsing code:
                                    int hStart = StringUtil.atoi(st.nextToken());
                                    int hLength = StringUtil.atoi(st.nextToken()) - hStart + 1;

                                    st.nextToken(); // bounds

                                    // these numbers are 1-offset, for
                                    // compatibility with RPS-BLAST parsing code:
                                    int start = StringUtil.atoi(st.nextToken());
                                    int l = StringUtil.atoi(st.nextToken()) - start + 1;

                                    // save this hit
                                    double coverage = (double)hLength / (double)modelLength;
                                    Tuple2<String, Long> contigIdFeatIndex = posToContigFeatIndex.get(featurePos);
                                    long featureIndex = contigIdFeatIndex.getE2();
                                    Map<String, List<Tuple5<Long, Long, Double, Double, Double>>> domains = contig2prots.get(contigIdFeatIndex.getE1()).get((int)featureIndex).getE5();
                                    List<Tuple5<Long, Long, Double, Double, Double>> places = domains.get(modelName);
                                    if (places == null) {
                                        places = new ArrayList<Tuple5<Long, Long, Double, Double, Double>>();
                                        domains.put(modelName, places);
                                    }
                                    places.add(new Tuple5<Long, Long, Double, Double, Double>()
                                               .withE1((long)start)
                                               .withE2((long)start + l - 1)
                                               .withE3(Double.parseDouble(eString))
                                               .withE4(score)
                                               .withE5(coverage));
                                }
                                catch (NoSuchElementException e) {
                                    throw new Exception("Format error in HMMER output line '"+buffer+"'");
                                }
                                buffer = infile.readLine();
                            }
                        }
                    }
                }
            }
            else
                throw new Exception("unsupported domain search program "+program);

            DomainAnnotation rv = new DomainAnnotation()
                .withGenomeRef(genomeRef)
                .withUsedDmsRef(domainModelSetRef)
                .withData(contig2prots)
                .withContigToSizeAndFeatureCount(contigSizes)
                .withFeatureToContigAndIndex(featIdToContigFeatIndex);
            return rv;
        }
        finally {
            try { fastaFile.delete(); } catch (Exception ignore) {}
            if (outFile != null)
                try { outFile.delete(); } catch (Exception ignore) {}
        }
    }

    public static File getBinDir() {
        File ret = new File("/kb/module/dependencies/bin");
        if (!ret.exists())
            ret.mkdir();
        return ret;
    }

    private static File getDomainsDir() {
        File ret = new File(tempDir, "domains");
        if (!ret.exists())
            ret.mkdir();
        return ret;
    }

    /**
       gets all the required library files out of shock.  Only
       supports publicly readable libraries for now (private libraries
       cannot currently be uploaded)
    */
    public static void prepareLibraryFiles(DomainLibrary dl,
                                           String shockURL,
                                           AuthToken token) throws Exception {
        File dir = getDomainsDir();
        for (Handle h : dl.getLibraryFiles()) {
            File f = new File(dir.getPath()+"/"+h.getFileName());
            if (f.canRead())
                continue;
            fromShock(h, shockURL, token, f, false);
        }
    }

    private static File getRpsBlastBin() throws Exception {
        return new File(getBinDir()+"/rpsblast.linux");
    }

    private static File getHmmerBin() throws Exception {
        return new File(getBinDir()+"/hmmscan.linux");
    }

    /**
       Runs RPS-BLAST on a file
    */
    public static File runRpsBlast(File dbFile, File fastaQuery) throws Exception {
        File tempOutputFile = File.createTempFile("rps", ".tab", tempDir);
        CorrectProcess cp = null;
        ByteArrayOutputStream errBaos = null;
        Exception err = null;
        String binPath = getRpsBlastBin().getAbsolutePath();
        int procExitValue = -1;
        FileOutputStream fos = new FileOutputStream(tempOutputFile);
        try {
            Process p = Runtime.getRuntime().exec(CorrectProcess.arr(binPath,
                                                                     "-db", dbFile.getAbsolutePath(),
                                                                     "-query", fastaQuery.getAbsolutePath(),
                                                                     "-outfmt", RpsBlastParser.OUTPUT_FORMAT_STRING,
                                                                     "-evalue", MAX_BLAST_EVALUE));
            errBaos = new ByteArrayOutputStream();
            cp = new CorrectProcess(p, fos, "", errBaos, "");
            p.waitFor();
            errBaos.close();
            procExitValue = p.exitValue();
        }
        catch(Exception ex) {
            try{
                errBaos.close();
            }
            catch (Exception ignore) {}
            try{
                if(cp!=null)
                    cp.destroy();
            }
            catch (Exception ignore) {}
            err = ex;
        }
        finally {
            try { fos.close(); } catch (Exception ignore) {}
        }
        if (errBaos != null) {
            String err_text = new String(errBaos.toByteArray());
            if (err_text.length() > 0)
                err = new Exception("RPS-BLAST: " + err_text, err);
        }
        if (procExitValue != 0) {
            if (err == null)
                err = new IllegalStateException("RPS-BLAST exit code: " + procExitValue);
            throw err;
        }
        return tempOutputFile;
    }

    /**
       Runs HMMER on a file
    */
    public static File runHmmer(File dbFile, File fastaQuery) throws Exception {
        File tempOutputFile = File.createTempFile("hmmer", ".txt", tempDir);
        CorrectProcess cp = null;
        ByteArrayOutputStream errBaos = null;
        Exception err = null;
        String binPath = getHmmerBin().getAbsolutePath();
        int procExitValue = -1;
        FileOutputStream fos = new FileOutputStream(tempOutputFile);
        try {
            Process p = Runtime.getRuntime().exec(CorrectProcess.arr(binPath,
                                                                     "--acc",
                                                                     "--notextw",
                                                                     "--cut_tc",
                                                                     dbFile.getAbsolutePath(),
                                                                     fastaQuery.getAbsolutePath()));
            errBaos = new ByteArrayOutputStream();
            cp = new CorrectProcess(p, fos, "", errBaos, "");
            p.waitFor();
            errBaos.close();
            procExitValue = p.exitValue();
        }
        catch(Exception ex) {
            try{
                errBaos.close();
            }
            catch (Exception ignore) {}
            try{
                if(cp!=null)
                    cp.destroy();
            }
            catch (Exception ignore) {}
            err = ex;
        }
        finally {
            try { fos.close(); } catch (Exception ignore) {}
        }
        if (errBaos != null) {
            String err_text = new String(errBaos.toByteArray());
            if (err_text.length() > 0)
                err = new Exception("HMMSCAN: " + err_text, err);
        }
        if (procExitValue != 0) {
            if (err == null)
                err = new IllegalStateException("HMMSCAN exit code: " + procExitValue);
            throw err;
        }
        return tempOutputFile;
    }

    public static void processRpsOutput(File results, RpsBlastParser.RpsBlastCallback callback) throws Exception {
        RpsBlastParser.processRpsOutput(results, callback);
    }
    
    /**
       calculate statistics to store in metadata,
       used for quick widget drawing
    */
    public static Map<String,String> getMetadata(DomainAnnotation ann) throws Exception {
        Map<String,String> metadata = new HashMap<String,String>();

        HashSet<String> domains = new HashSet<String>();
        HashSet<String> features = new HashSet<String>();

        Map<String, List<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>>> data = ann.getData();
        for (String contigID : data.keySet()) {
            List<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>> annElements = data.get(contigID);
            ListIterator<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>> iterator = annElements.listIterator();
            while (iterator.hasNext()) {
                Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>> elements = iterator.next();
                Map<String, List<Tuple5<Long, Long, Double, Double, Double>>> element = elements.getE5();
                if (element != null) {
                    for (String key : element.keySet()) {
                        domains.add(elements.getE1());
                        features.add(key);
                    }
                }
            }
        }

        metadata.put("annotated_domains",""+domains.size());
        metadata.put("annotated_features",""+features.size());

        return metadata;
    }
    
    /**
       combines annotation data from two DomainAnnotation objects;
       must be from the same genome.  Note that this will fail if
       results are in different order, or if two libraries have
       models with the same accessions
    */
    public static void combineData(DomainAnnotation source,
                                   DomainAnnotation target) throws Exception {
        if (!source.getGenomeRef().equals(target.getGenomeRef()))
            throw new IllegalArgumentException("Error: DomainAnnotation objects from different genomes can't be combined");
        if (!source.getUsedDmsRef().equals(target.getUsedDmsRef()))
            throw new IllegalArgumentException("Error: DomainAnnotation objects from different domain model sets can't be combined");

        Map<String, List<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>>> sourceData = source.getData();
        Map<String, List<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>>> targetData = target.getData();
        for (String contigID : sourceData.keySet()) {
            List<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>> sourceElements = sourceData.get(contigID);
            List<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>> targetElements = targetData.get(contigID);
            ListIterator<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>> sIterator = sourceElements.listIterator();
            ListIterator<Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>>> tIterator = targetElements.listIterator();
            while (sIterator.hasNext()) {
                Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>> sElement = sIterator.next();
                Tuple5<String, Long, Long, Long, Map<String, List<Tuple5<Long, Long, Double, Double, Double>>>> tElement = tIterator.next();
                tElement.getE5().putAll(sElement.getE5());
            }
        }
    }
}
