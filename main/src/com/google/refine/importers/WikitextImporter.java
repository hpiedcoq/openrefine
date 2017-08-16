package com.google.refine.importers;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.io.CharStreams;
import de.fau.cs.osr.ptk.common.AstVisitor;

import org.sweble.wikitext.parser.ParserConfig;
import org.sweble.wikitext.parser.utils.SimpleParserConfig;
import org.sweble.wikitext.parser.WikitextParser;
import org.sweble.wikitext.parser.nodes.WtBold;
import org.sweble.wikitext.parser.nodes.WtItalics;
import org.sweble.wikitext.parser.nodes.WtNode;
import org.sweble.wikitext.parser.nodes.WtSection;
import org.sweble.wikitext.parser.nodes.WtText;
import org.sweble.wikitext.parser.nodes.WtInternalLink;
import org.sweble.wikitext.parser.nodes.WtExternalLink;
import org.sweble.wikitext.parser.nodes.WtLinkTitle;
import org.sweble.wikitext.parser.nodes.WtLinkTitle.WtNoLinkTitle;
import org.sweble.wikitext.parser.nodes.WtUrl;
import org.sweble.wikitext.parser.nodes.WtTable;
import org.sweble.wikitext.parser.nodes.WtTableHeader;
import org.sweble.wikitext.parser.nodes.WtTableRow;
import org.sweble.wikitext.parser.nodes.WtTableCell;
import org.sweble.wikitext.parser.nodes.WtTableCaption;
import org.sweble.wikitext.parser.nodes.WtXmlAttributes;
import org.sweble.wikitext.parser.nodes.WtXmlAttribute;
import org.sweble.wikitext.parser.nodes.WtName;
import org.sweble.wikitext.parser.nodes.WtValue;
import org.sweble.wikitext.parser.nodes.WtParsedWikitextPage;
import org.sweble.wikitext.parser.nodes.WtBody;

import org.sweble.wikitext.parser.WikitextEncodingValidator;
import org.sweble.wikitext.parser.WikitextPreprocessor;
import org.sweble.wikitext.parser.encval.ValidatedWikitext;
import org.sweble.wikitext.parser.nodes.WtParsedWikitextPage;
import org.sweble.wikitext.parser.nodes.WtPreproWikitextPage;
import org.sweble.wikitext.parser.parser.PreprocessorToParserTransformer;
import org.sweble.wikitext.parser.preprocessor.PreprocessedWikitext;

import xtc.parser.ParseException;

import com.google.refine.ProjectMetadata;
import com.google.refine.importing.ImportingJob;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.model.Recon;
import com.google.refine.model.ReconCandidate;
import com.google.refine.model.ReconStats;
import com.google.refine.model.recon.StandardReconConfig.ColumnDetail;
import com.google.refine.util.JSONUtilities;
import com.google.refine.model.recon.StandardReconConfig;
import com.google.refine.model.recon.ReconJob;


public class WikitextImporter extends TabularImportingParserBase {
    static final private Logger logger = LoggerFactory.getLogger(WikitextImporter.class);
    
    public WikitextImporter() {
        super(false);
    }
    
    @Override
    public JSONObject createParserUIInitializationData(
            ImportingJob job, List<JSONObject> fileRecords, String format) {
        JSONObject options = super.createParserUIInitializationData(job, fileRecords, format);
        
        JSONUtilities.safePut(options, "guessCellValueTypes", false);
        JSONUtilities.safePut(options, "blankSpanningCells", true);
        JSONUtilities.safePut(options, "wikiUrl", "https://en.wikipedia.org/wiki/");
        
        return options;
    }
    
    private class SpanningCell {
        public String value;
        public String reconciled;
        public int colspan;
        public int rowspan;
        public int row;
        public int col;
        
        SpanningCell(String value, String reconciled, int row, int col, int rowspan, int colspan) {
            this.value = value;
            this.reconciled = reconciled;
            this.row = row;
            this.col = col;
            this.rowspan = rowspan;
            this.colspan = colspan;
        }
    }
    
    private class WikilinkedCell {
        public String internalLink;
        public int row;
        public int col;
        
        WikilinkedCell(String internalLink, int row, int col) {
            this.internalLink = internalLink;
            this.row = row;
            this.col = col;
        }
        
        public String toURL(String wikiBaseUrl) {
            return wikiBaseUrl + internalLink;
        }
    }
    
    public class WikitextTableVisitor extends AstVisitor<WtNode> {
        
        public String caption;
        public List<String> header;
        public List<List<String>> rows;
        public List<WikilinkedCell> wikilinkedCells;
        private List<String> currentRow;
        
        private boolean blankSpanningCells;
        
        private int rowId;
        private List<SpanningCell> spanningCells;
        private StringBuilder cellStringBuilder;
        private StringBuilder xmlAttrStringBuilder;
        private String currentXmlAttr;
        private String currentInternalLink;
        private String currentExternalLink;
        private int colspan;
        private int rowspan;
        private int spanningCellIdx;
        private List<String> internalLinksInCell;
        
        public WikitextTableVisitor(boolean blankSpanningCells) {
            this.blankSpanningCells = blankSpanningCells;
            caption = null;
            header = new ArrayList<String>();
            rows = new ArrayList<List<String>>();
            wikilinkedCells = new ArrayList<WikilinkedCell>();
            spanningCells = new ArrayList<SpanningCell>();
            cellStringBuilder = null;
            xmlAttrStringBuilder = null;
            currentInternalLink = null;
            currentExternalLink = null;
            colspan = 0;
            rowspan = 0;
            rowId = -1;
            spanningCellIdx = 0;
            internalLinksInCell = new ArrayList<String>();
        }
        
        @Override
        protected WtNode before(WtNode node) {
            return super.before(node);
        }
        
        /* Default handler */
        
        public void visit(WtNode e) {
            // Ignore other nodes
            // System.out.println(e.getNodeName());
        }
        
        /* Table handling */
        
        public void visit(WtTable e) {
            iterate(e);
        }
        
        public void visit(WtTableHeader e) {
            String columnName = renderCellAsString(e);
            header.add(columnName);
            // For the header, we ignore rowspan and manually add cells for colspan
            if (colspan > 1) {
                for (int i = 0; i < colspan-1; i++) {
                    header.add(columnName);
                }
            }
        }
        
        public void visit(WtTableCaption e) {
            caption = renderCellAsString(e);
        }
 
        public void visit(WtTableRow e)
        {
            if (currentRow == null) {
                if (rowId == -1) {
                    // no header was found, start on the first row
                    rowId = 0;
                }
                currentRow = new ArrayList<String>();
                spanningCellIdx = 0;
                addSpanningCells();
                iterate(e);
                if(currentRow.size() > 0) {
                    rows.add(currentRow);
                    rowId++;
                } 
                currentRow = null;
            }
        }
        
        public void visit(WtTableCell e)
        {
            if (currentRow != null) {
                rowspan = 1;
                colspan = 1;
                internalLinksInCell.clear();
                String value = renderCellAsString(e);
                
                int colId = currentRow.size();
                
                // Add the cell to the row we are currently building
                currentRow.add(value);
                
                // Reconcile it if we found exactly one link in the cell
                String reconciled = null;
                if (internalLinksInCell.size() == 1) {
                    reconciled = internalLinksInCell.get(0);
                    wikilinkedCells.add(new WikilinkedCell(reconciled, rowId, colId));
                }
                
                // Mark it as spanning if we found the tags
                if (colspan > 1 || rowspan > 1) {
                    SpanningCell spanningCell = new SpanningCell(
                        value, reconciled, rowId, colId, rowspan, colspan);
                    spanningCells.add(spanningCellIdx, spanningCell);
                }
                
                // Add all spanning cells that need to be inserted after this one.
                addSpanningCells();
            }
        }
        
        public String renderCellAsString(WtNode e) {
            cellStringBuilder = new StringBuilder();
            iterate(e);
            String value = cellStringBuilder.toString();
            if (value == null) {
                value = "";
            }
            value = value.trim();
            cellStringBuilder = null;
            return value;
        }
        
        public void visit(WtText text) {
            if (xmlAttrStringBuilder != null) {
                xmlAttrStringBuilder.append(text.getContent());
            } else if (cellStringBuilder != null) {
                cellStringBuilder.append(text.getContent());
            }
        }
        
        /* Spanning cell helpers */
        
        private SpanningCell spanningCell() {
            return spanningCells.get(spanningCellIdx);
        }
        
        private void addSpanningCells() {
            while (spanningCellIdx < spanningCells.size() &&
                    currentRow.size() >= spanningCell().col) {
                // Add blank cells to represent the current spanning cell
                SpanningCell cell = spanningCell();
                if (cell.row + cell.rowspan >= rowId + 1) {
                    while(currentRow.size() < cell.col + cell.colspan) {
                        if (blankSpanningCells) {
                            currentRow.add(null);
                        } else {
                            currentRow.add(cell.value);
                            if (cell.reconciled != null) {
                                wikilinkedCells.add(new WikilinkedCell(cell.reconciled, rowId, currentRow.size()-1));
                            }
                        }
                    }
                }
                // Check if this spanning cell has been fully represented
                if(cell.row + cell.rowspan <= rowId + 1) {
                    spanningCells.remove(spanningCellIdx);
                } else {
                    spanningCellIdx++;
                }
            }
        }
        
        /* XML attributes : useful for colspan and rowspan */
        
        public void visit(WtXmlAttributes e) {
            iterate(e);
        }
        
        public void visit(WtXmlAttribute e) {
            if (currentXmlAttr == null) {
                xmlAttrStringBuilder = new StringBuilder();
                iterate(e);
                try {
                    int attrValue = Integer.parseInt(xmlAttrStringBuilder.toString());
                    if ("colspan".equals(currentXmlAttr)) {
                        colspan = attrValue;
                    } else if ("rowspan".equals(currentXmlAttr)) {
                        rowspan = attrValue;
                    }
                } catch (NumberFormatException _) {
                }
                currentXmlAttr = null;
                xmlAttrStringBuilder = null;
            }
        }
        
        public void visit(WtName e) {
            currentXmlAttr = e.getAsString();
        }
        
        public void visit(WtValue e) {
            iterate(e);
        }
        
        /* Link management */
        
        
        public void visit(WtInternalLink e) {
            currentInternalLink = e.getTarget().getAsString();
            internalLinksInCell.add(currentInternalLink);
            iterate(e);
            currentInternalLink = null;
        }
        
        public void visit(WtExternalLink e) {
            WtUrl url = e.getTarget();
            String externalLink = url.getProtocol() + ":" + url.getPath();
            if (cellStringBuilder != null) {
                if(rowId >= 0) {
                    // We are inside the table: all hyperlinks
                    // should be converted to their URLs regardless of
                    // their label.
                    cellStringBuilder.append(externalLink);
                } else {
                    // We are in the header: keep the labels instead
                    currentExternalLink = externalLink;
                    iterate(e);
                    currentExternalLink = null;
                }
            }
        }
        
        public void visit(WtNoLinkTitle e) {
            if (cellStringBuilder != null) {
                if (currentInternalLink != null) {
                    cellStringBuilder.append(currentInternalLink);
                } else if (currentExternalLink != null) {
                    cellStringBuilder.append(currentExternalLink);
                }
            }
        }
        
        public void visit(WtLinkTitle e) {
            iterate(e);
        }
        
        public void visit(WtUrl e) {
            // already handled, in WtExternalLink, added here for clarity
        }
        
        /* Content blocks */
        
        public void visit(WtParsedWikitextPage e) {
            iterate(e);
        }
        
        public void visit(WtSection e) {
            iterate(e);
        }
        
        public void visit(WtBody e) {
            iterate(e);
        }
        
        public void visit(WtItalics e) {
            iterate(e);
        }
        
        public void visit(WtBold e) {
            iterate(e);
        }
        
        @Override
        protected Object after(WtNode node, Object result)
        {
            return rows;
        }
    }
    
    public class WikiTableDataReader implements TableDataReader {
        private int currentRow = -1;
        private WikitextTableVisitor visitor = null;
        private List<List<Recon>> reconList = null;
        private List<Boolean> columnReconciled = null;
    
        public WikiTableDataReader(WikitextTableVisitor visitor) {
            this.visitor = visitor;
            currentRow = -1;
            reconList = null;
        }
        
        @Override
        public List<Object> getNextRowOfCells() throws IOException {
            List<Object> row = null;
            List<String> origRow = null;
            if (currentRow == -1) {
                origRow = this.visitor.header;
            } else if(currentRow < this.visitor.rows.size()) {
                origRow = this.visitor.rows.get(currentRow);
            }
            
            if (origRow != null) {
                row = new ArrayList<Object>();
                for (int i = 0; i < origRow.size(); i++) {
                    Recon recon = null;
                    if (currentRow >= 0 && reconList != null) {
                        recon = reconList.get(currentRow).get(i);
                    }
                    row.add(new Cell(origRow.get(i), recon));
                }
            }
            currentRow++;
            return row;
        }
        
        private void reconcileToQids(String wikiBaseUrl, StandardReconConfig cfg) {
            if("null".equals(wikiBaseUrl)) {
                return; // TODO: more thorough URL validation instead
            }
            
            // Init the list of recons
            reconList = new ArrayList<List<Recon>>();
            columnReconciled = new ArrayList<Boolean>();
            for (int i = 0; i < this.visitor.rows.size(); i++) {
                int rowSize = this.visitor.rows.get(i).size();
                List<Recon> recons = new ArrayList<Recon>(rowSize);
                for (int j = 0; j < rowSize; j++) {
                    recons.add(null);
                    if (i == 0)
                        columnReconciled.add(false);
                }
                reconList.add(recons);
                
            }
            
            int batchSize = 50;
            int i = 0;
            int totalSize = this.visitor.wikilinkedCells.size();
            while (i < totalSize) {
                List<ReconJob> jobs = new ArrayList<ReconJob>();
                int batchStart = i;
                while (i < batchStart + batchSize && i < totalSize) {
                    WikilinkedCell cell = this.visitor.wikilinkedCells.get(i);
                    jobs.add(cfg.createSimpleJob(cell.toURL(wikiBaseUrl)));
                    i++;
                }
                
                List<Recon> recons = cfg.batchRecon(jobs, 0);
                for (int j = batchStart; j < batchStart + batchSize && j < totalSize; j++) {
                    WikilinkedCell cell = this.visitor.wikilinkedCells.get(j);
                    Recon recon = recons.get(j - batchStart);
                    if (recon != null) {
                        reconList.get(cell.row).set(cell.col, recon);
                        columnReconciled.set(cell.col, true);
                    }
                }
            }        
        }
    }

    @Override
    public void parseOneFile(
        Project project,
        ProjectMetadata metadata,
        ImportingJob job,
        String fileSource,
        Reader reader,
        int limit,
        JSONObject options,
        List<Exception> exceptions
    ) {
        // Set-up a simple wiki configuration
        ParserConfig parserConfig = new SimpleParserConfig();
        
        try {
            // Encoding validation

            WikitextEncodingValidator v = new WikitextEncodingValidator();

            String wikitext = CharStreams.toString(reader);
            String title = "Page title";
            ValidatedWikitext validated = v.validate(parserConfig, wikitext, title);

            // Pre-processing
            WikitextPreprocessor prep = new WikitextPreprocessor(parserConfig);

            WtPreproWikitextPage prepArticle =
                            (WtPreproWikitextPage) prep.parseArticle(validated, title, false);

            // Parsing
            PreprocessedWikitext ppw = PreprocessorToParserTransformer
                            .transform(prepArticle);

            WikitextParser parser = new WikitextParser(parserConfig);

            WtParsedWikitextPage parsedArticle;
            parsedArticle = (WtParsedWikitextPage) parser.parseArticle(ppw, title);
            
            // Compile the retrieved page
            boolean blankSpanningCells = JSONUtilities.getBoolean(options, "blankSpanningCells", true);
            final WikitextTableVisitor vs = new WikitextTableVisitor(blankSpanningCells);
            vs.go(parsedArticle);
            
            WikiTableDataReader dataReader = new WikiTableDataReader(vs);
            
            // Reconcile if needed
            String wikiUrl = JSONUtilities.getString(options, "wikiUrl", null);
            // Wikidata reconciliation endpoint, hardcoded because the user might not have it in its services
            String reconUrl = JSONUtilities.getString(options, "reconService",
                  "https://tools.wmflabs.org/openrefine-wikidata/en/api");
            StandardReconConfig cfg = getReconConfig(reconUrl);

            if (wikiUrl != null) {
                dataReader.reconcileToQids(wikiUrl, cfg);
            }
            
            JSONUtilities.safePut(options, "headerLines", 1);
            
            // Set metadata
            if (vs.caption != null && vs.caption.length() > 0) {
                metadata.setName(vs.caption);
                // TODO this does not seem to do anything - maybe we need to pass it to OpenRefine in some other way?
            }

            TabularImportingParserBase.readTable(project, metadata, job, dataReader, fileSource, limit, options, exceptions);
            
            // Add reconciliation statistics
            if (dataReader.columnReconciled != null) {
                for(int i = 0; i != dataReader.columnReconciled.size(); i++) {
                    if (dataReader.columnReconciled.get(i)) {
                        Column col = project.columnModel.columns.get(i);
                        col.setReconStats(ReconStats.create(project, i));
                        col.setReconConfig(cfg);
                    }
                }
            }
        } catch (IOException e1) {
            e1.printStackTrace();
        } catch (ParseException e1) {
            exceptions.add(e1);
            e1.printStackTrace();
        }
    }
    
    private StandardReconConfig getReconConfig(String url) {
        StandardReconConfig cfg = new StandardReconConfig(
            url,
            "http://www.wikidata.org/entity/",
            "http://www.wikidata.org/prop/direct/",
            "", 
            "entity",
            true,
            new ArrayList<ColumnDetail>(),
            1
        );
        return cfg;
    }

}
