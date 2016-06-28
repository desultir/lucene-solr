package org.apache.solr.response;

import org.apache.lucene.index.IndexableField;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.schema.StrField;
import org.apache.solr.search.DocList;
import org.apache.solr.search.ReturnFields;
import org.apache.solr.core.SolrCore;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.io.Writer;
import java.io.CharArrayWriter;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.OutputStream;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/**
 *
 */

public class XLSXResponseWriter extends RawResponseWriter {
  Logger log = LoggerFactory.getLogger(SolrCore.class);

  @Override
  public void init(NamedList n) {
  }

  @Override
  public void write(OutputStream out, SolrQueryRequest req, SolrQueryResponse rsp) throws IOException {
    //throwaway arraywriter just to satisfy super requirements; we're grabbing
    //all writes before they go to it anyway
    XLSXWriter w = new XLSXWriter(new CharArrayWriter(), req, rsp);
    try {
      w.writeResponse(out);
      w.close();
    } catch (IOException e) {
      log.warn("Write response failed due to IOException");
      log.warn(e.getMessage());
      w.close();
    }
  }

  @Override
  public String getContentType(SolrQueryRequest request, SolrQueryResponse response) {
    return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  }
}

class XLSXWriter extends TextResponseWriter {

  SolrQueryRequest req;
  SolrQueryResponse rsp;

  Logger log = LoggerFactory.getLogger(SolrCore.class);

  class SerialWriteWorkbook {
    SXSSFWorkbook swb;
    Sheet sh;

    XSSFCellStyle headerStyle;
    int rowIndex;
    Row curRow;
    int cellIndex;

    SerialWriteWorkbook() {
      this.swb = new SXSSFWorkbook(100);
      this.sh = this.swb.createSheet();

      this.rowIndex = 0;

      this.headerStyle = (XSSFCellStyle)swb.createCellStyle();
      this.headerStyle.setFillBackgroundColor(IndexedColors.BLACK.getIndex());
      //solid fill
      this.headerStyle.setFillPattern((short)1);
      Font headerFont = swb.createFont();
      headerFont.setFontHeightInPoints((short)14);
      headerFont.setBoldweight(Font.BOLDWEIGHT_BOLD);
      headerFont.setColor(IndexedColors.WHITE.getIndex());
      this.headerStyle.setFont(headerFont);
    }

    void addRow() {
      curRow = sh.createRow(rowIndex++);
      cellIndex = 0;
    }

    void setHeaderRow() {
      curRow.setHeightInPoints((short)21);
    }

    //sets last created cell to have header style
    void setHeaderCell() {
      curRow.getCell(cellIndex - 1).setCellStyle(this.headerStyle);
    }

    //set the width of the most recently created column
    void setColWidth(int charWidth) {
      //width is set in units of 1/256th of a character width for some reason
      this.sh.setColumnWidth(cellIndex - 1, 256*charWidth);
    }

    void writeCell(String value) {
      Cell cell = curRow.createCell(cellIndex++);
      cell.setCellValue(value);
    }

    void flush(OutputStream out) {
      try {
        swb.write(out);
        log.info("Flush complete");
      } catch (IOException e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String stacktrace = sw.toString();
        log.warn("Failed to export to XLSX - "+stacktrace);
      }finally {
        swb.dispose();
      }
    }
  }

  private SerialWriteWorkbook wb = new SerialWriteWorkbook();

  static class Field {
    String name;
    SchemaField sf;
  }

  private Map<String,Field> xlFields = new LinkedHashMap<String,Field>();

  public XLSXWriter(Writer writer, SolrQueryRequest req, SolrQueryResponse rsp){
    super(writer, req, rsp);
    this.req = req;
    this.rsp = rsp;
  }

  public void writeResponse(OutputStream out) throws IOException {
    log.info("Beginning export");

    Collection<String> fields = returnFields.getRequestedFieldNames();
    Object responseObj = rsp.getValues().get("response");
    boolean returnOnlyStored = false;
    //TODO check this against CSVResponseWriter, mostly from there
    if (fields==null||returnFields.hasPatternMatching()) {
      if (responseObj instanceof SolrDocumentList) {
        // get the list of fields from the SolrDocumentList
        if(fields==null) {
          fields = new LinkedHashSet<String>();
        }
        for (SolrDocument sdoc: (SolrDocumentList)responseObj) {
          fields.addAll(sdoc.getFieldNames());
        }
      } else {
        // get the list of all fields in the index
        Iterable<String> allFields = req.getSearcher().getFieldNames();
        if(fields==null) {
          fields = new ArrayList<String>();
        }
        for (String fieldName: allFields) {
          fields.add(fieldName);
        }
      }
      if (returnFields.wantsScore()) {
        fields.add("score");
      } else {
        fields.remove("score");
      }
      returnOnlyStored = true;
    }

    for (String field : fields) {
      if (!returnFields.wantsField(field)) {
        continue;
      }
      if (field.equals("score")) {
        Field xlField = new Field();
        xlField.name = "score";
        xlFields.put("score", xlField);
        continue;
      }

      SchemaField sf = schema.getFieldOrNull(field);
      if (sf == null) {
        FieldType ft = new StrField();
        sf = new SchemaField(field, ft);
      }

      // Return only stored fields, unless an explicit field list is specified
      if (returnOnlyStored && sf != null && !sf.stored()) {
        continue;
      }

      Field xlField = new Field();
      xlField.name = field;
      xlField.sf = sf;
      xlFields.put(field, xlField);
    }

    //TODO remove both of these
    class NameAndWidth {
      private int width;
      private String name;

      public NameAndWidth(String name, int width) {
        this.name = name;
        this.width = width;
      }

      public String getName() {
        return this.name;
      }

      public int getWidth() {
        return this.width;
      }
    }

    class NiceMetadataNames extends LinkedHashMap<String, NameAndWidth> {

      public NiceMetadataNames() {
        super();
        this.put("meta_type_1", new NameAndWidth("Nice Name 1", 14));
        this.put("long_meta_2", new NameAndWidth("Long Metadata name is long", 128));
      }
    }

    //TODO and get this from the xml config
    NiceMetadataNames niceMap = new NiceMetadataNames();

    wb.addRow();
    //write header
    for (Field xlField : xlFields.values()) {
      String printName = xlField.name;
      int colWidth = 14;

      NameAndWidth nextField = niceMap.get(xlField.name);
      if (nextField != null) {
        printName = nextField.getName();;
        colWidth = nextField.getWidth();
      }

      writeStr(xlField.name, printName, false);
      wb.setColWidth(colWidth);
      wb.setHeaderCell();
    }
    wb.setHeaderRow();
    wb.addRow();

    //write rows
    //TODO check this against CSVResponseWriter, mostly from there
    if (responseObj instanceof ResultContext ) {
      writeDocuments("",(ResultContext)responseObj, returnFields );
    }
    else if (responseObj instanceof DocList) {
      ResultContext ctx = new ResultContext();
      ctx.docs =  (DocList)responseObj;
      writeDocuments(null, ctx, returnFields );
    } else if (responseObj instanceof SolrDocumentList) {
      writeSolrDocumentList(null, (SolrDocumentList)responseObj, returnFields );
    }
    log.info("Export complete; flushing document");
    //flush to outputstream
    wb.flush(out);
    wb = null;

  }

  @Override
  public void close() throws IOException {
    super.close();
  }

  @Override
  public void writeNamedList(String name, NamedList val) throws IOException {
  }

  @Override
  public void writeStartDocumentList(String name,
                                     long start, int size, long numFound, Float maxScore) throws IOException
  {
    // nothing
  }

  @Override
  public void writeEndDocumentList() throws IOException
  {
    // nothing
  }

  //NOTE: a document cannot currently contain another document
  List tmpList;
  @Override
  public void writeSolrDocument(String name, SolrDocument doc, ReturnFields returnFields, int idx ) throws IOException {
    if (tmpList == null) {
      tmpList = new ArrayList(1);
      tmpList.add(null);
    }

    for (Field xlField : xlFields.values()) {
      Object val = doc.getFieldValue(xlField.name);
      int nVals = val instanceof Collection ? ((Collection)val).size() : (val==null ? 0 : 1);
      if (nVals == 0) {
        writeNull(xlField.name);
        continue;
      }

      if ((xlField.sf != null && xlField.sf.multiValued()) || nVals > 1) {
        Collection values;
        // normalize to a collection
        if (val instanceof Collection) {
          values = (Collection)val;
        } else {
          tmpList.set(0, val);
          values = tmpList;
        }

        writeArray(xlField.name, values.iterator());

      } else {
        // normalize to first value
        if (val instanceof Collection) {
          Collection values = (Collection)val;
          val = values.iterator().next();
        }
        writeVal(xlField.name, val);
      }
    }
    wb.addRow();
  }

  @Override
  public void writeStr(String name, String val, boolean needsEscaping) throws IOException {
    wb.writeCell(val);
  }

  @Override
  public void writeMap(String name, Map val, boolean excludeOuter, boolean isFirstVal) throws IOException {
  }

  @Override
  public void writeArray(String name, Iterator val) throws IOException {
    StringBuffer output = new StringBuffer();
    while (val.hasNext()) {
      Object v = val.next();
      if (v instanceof IndexableField) {
        IndexableField f = (IndexableField)v;
        output.append(f.stringValue() + "; ");
      } else {
        output.append(v.toString() + "; ");
      }
    }
    if (output.length() > 0) {
      output.deleteCharAt(output.length()-1);
      output.deleteCharAt(output.length()-1);
    }
    writeStr(name, output.toString(), false);
  }

  @Override
  public void writeNull(String name) throws IOException {
    wb.writeCell("");
  }

  @Override
  public void writeInt(String name, String val) throws IOException {
    wb.writeCell(val);
  }

  @Override
  public void writeLong(String name, String val) throws IOException {
    wb.writeCell(val);
  }

  @Override
  public void writeBool(String name, String val) throws IOException {
    wb.writeCell(val);
  }

  @Override
  public void writeFloat(String name, String val) throws IOException {
    wb.writeCell(val);
  }

  @Override
  public void writeDouble(String name, String val) throws IOException {
    wb.writeCell(val);
  }

  @Override
  public void writeDate(String name, Date val) throws IOException {
    String outputDate = DateTimeFormatter.ISO_LOCAL_DATE.format(val.toInstant());
    writeDate(name, outputDate);
  }

  @Override
  public void writeDate(String name, String val) throws IOException {
    wb.writeCell(val);
  }
}