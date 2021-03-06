/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package act.installer.sequence;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;

import act.server.MongoDB;
import act.shared.Seq;
import act.shared.helpers.MongoDBToJSON;
import act.shared.sar.SAR;

import com.mongodb.DBObject;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.XML;
import org.json.JSONException;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

public class SwissProtEntry extends SequenceEntry {
  JSONObject data;

  public static void parsePossiblyMany(File uniprot_file, final MongoDB db) throws IOException {
    try (
        FileInputStream fis = new FileInputStream(uniprot_file);
    ) {
      SwissProtEntryHandler handler = new SwissProtEntryHandler() {
        @Override
        public void handle(SwissProtEntry entry) {
          // TODO: run this in a separate thread w/ a synchronized queue to connect it to the parser.
          entry.writeToDB(db, Seq.AccDB.swissprot);
        }
      };
      parsePossiblyMany(handler, fis, uniprot_file.toString());
    }
  }

  public static Set<SequenceEntry> parsePossiblyMany(String is) throws IOException {
    final HashSet<SequenceEntry> results = new HashSet<SequenceEntry>();
    SwissProtEntryHandler handler = new SwissProtEntryHandler() {
      @Override
      public void handle(SwissProtEntry entry) {
        results.add(entry);
      }
    };
    InputStream sis = new ByteArrayInputStream(is.getBytes(StandardCharsets.UTF_8));
    parsePossiblyMany(handler, sis, "[String input]");
    return results;
  }

  private static void parsePossiblyMany(SwissProtEntryHandler handler,
                                        InputStream is, String debugSource) throws IOException {
    XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
    XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newInstance();
    XMLEventReader xr = null;

    try {
      xr = xmlInputFactory.createXMLEventReader(is, "utf-8");

      /* The following few lines would look more natural as `new XMLEventWriter(new StringWriter())`, which sets up an
       * event chain that looks like `[Xml Event] -> {XMLEventWriter -> StringWriter} -> String`.  The writers are in
       * brackets because they're composed via these next few lines.  The XMLEventWriter does the interpretation and
       * serialization of the events, and the StringWriter acts as a buffer (which is why we need a reference to the
       * StringWriter).
       * */
      StringWriter w = new StringWriter();
      XMLEventWriter xw = xmlOutputFactory.createXMLEventWriter(w);

      boolean inEntry = false;
      int processedEntries = 0;
      while (xr.hasNext()) {
        XMLEvent e = xr.nextEvent();
        if (!inEntry && e.isStartElement() && e.asStartElement().getName().getLocalPart().equals(("entry"))) {
          // Found <entry>.
          inEntry = true;
        } else if (e.isEndElement() && e.asEndElement().getName().getLocalPart().equals("entry")) {
          // Found </entry>.
          // Ensure that the XMLEventWriter has processed all events and sent them to the StringWriter it wraps.
          xw.flush();

          // w.toString() gets a textual representation of all the XML events we've sent to xw.
          String xmlText = w.toString();
          SwissProtEntry entry = new SwissProtEntry(XML.toJSONObject(xmlText));
          handler.handle(entry);
          /* Reset the XMLEventWriter(StringWriter()) chain to prepare for the next <entry> we find.
           * Note: this can also be accomplished with `w.getBuffer().setLength(0);`, but using a new event writer
           * seems safer. */
          xw.close();
          w = new StringWriter();
          xw = xmlOutputFactory.createXMLEventWriter(w);
          inEntry = false;

          processedEntries++;
          if (processedEntries % 10000 == 0) {
            // TODO: proper logging!
            System.out.println("Processed " + processedEntries + " UniProt/SwissProt entries from " + debugSource);
          }
        } else if (inEntry) {
          // Found some element inside of an <entry></entry> element--just send it to the event stream.
          xw.add(e);
        }
      }
      xr.close();
      if (xw != null) {
        xw.close();
      }
      System.out.println("Completed processing " + processedEntries + " UniProt/SwissProt entries from " + debugSource);
    } catch (JSONException je) {
      System.out.println("Failed SwissProt parse: " + je.toString() + " XML file: " + debugSource);
    } catch (XMLStreamException e) {
      // TODO: do better.
      throw new IOException(e);
    }
  }

  private SwissProtEntry(JSONObject gene_entry) {
    this.data = gene_entry;
  }

  String getEc() {
    // data.dbReference.[{id:x.x.x.x, type:"EC"}...]
    return lookup_ref(this.data, "EC");
  }

  DBObject getMetadata() {
    return MongoDBToJSON.conv(this.data);
  }

  Set<Long> getCatalyzedRxns() {
    // optionally add reactions to actfamilies by processing
    // "catalytic activity" annotations and then return those
    // catalyzed reaction ids (Long _id of actfamilies). This
    // function SHOULD NOT infer which actfamilies refer to
    // this object, as that is done in map_seq install.
    return new HashSet<Long>();
  }

  Set<Long> getCatalyzedSubstratesDiverse() {
    // see comment in get_catalyzed_rxns for the function here
    // when we want to NLP/parse out the "catalysis activity"
    // field, we will return that here.
    return new HashSet<Long>();
  }

  Set<Long> getCatalyzedProductsDiverse() {
    // see comment in get_catalyzed_rxns for the function here
    // when we want to NLP/parse out the "catalysis activity"
    // field, we will return that here.
    return new HashSet<Long>();
  }

  Set<Long> getCatalyzedSubstratesUniform() {
    // see comment in get_catalyzed_rxns for the function here
    // when we want to NLP/parse out the "catalysis activity"
    // field, we will return that here.
    return new HashSet<Long>();
  }

  Set<Long> getCatalyzedProductsUniform() {
    // see comment in get_catalyzed_rxns for the function here
    // when we want to NLP/parse out the "catalysis activity"
    // field, we will return that here.
    return new HashSet<Long>();
  }

  HashMap<Long, Set<Long>> getCatalyzedRxnsToSubstrates() {
    // see comment in get_catalyzed_rxns for the function here
    // when we want to NLP/parse out the "catalysis activity"
    // field, we will return that here.
    return new HashMap<Long, Set<Long>>();
  }

  HashMap<Long, Set<Long>> getCatalyzedRxnsToProducts() {
    // see comment in get_catalyzed_rxns for the function here
    // when we want to NLP/parse out the "catalysis activity"
    // field, we will return that here.
    return new HashMap<Long, Set<Long>>();
  }

  SAR getSar() {
    // sar is computed later; using "initdb infer_sar"
    // for now add the empty sar constraint set
    return new SAR();
  }

  List<JSONObject> getRefs() {
    // data.reference.[ {citation: {type: "journal article", dbReference.{id:, type:PubMed}, title:XYA } ... } .. ]
    List<String> pmids = new ArrayList<String>();
    JSONArray refs = possible_list(this.data.get("reference"));
    for (int i = 0; i<refs.length(); i++) {
      JSONObject citation = (JSONObject)((JSONObject)refs.get(i)).get("citation");
      if (citation.get("type").equals("journal article")) {
        String id = lookup_ref(citation, "PubMed");
        if (id != null) pmids.add(id);
      }
    }

    List<JSONObject> pmid_references = new ArrayList<>();
    for (String pmid : pmids) {
      JSONObject obj = new JSONObject();
      obj.put("val", pmid);
      obj.put("src", "PMID");
      pmid_references.add(obj);
    }

    return pmid_references;
  }

  Long getOrgId() {
    // data.organism.dbReference.{id: 9606, type: "NCBI Taxonomy"}
    String id = lookup_ref(this.data.get("organism"), "NCBI Taxonomy");
    if (id == null) return null;
    return Long.parseLong(id);
  }

  String getSeq() {
    // data.sequence.content: "MSTAGKVIKCKAAV.."
    return (String)((JSONObject)this.data.get("sequence")).get("content");
  }

  private String lookup_ref(Object o, String typ) {
    // o.dbReference.{id: 9606, type: typ}
    // o.dbReference.[{id: x.x.x.x, type: typ}]
    JSONObject x = (JSONObject)o;
    if (!x.has("dbReference"))
      return null;

    JSONArray set = possible_list(x.get("dbReference"));

    for (int i = 0; i<set.length(); i++) {
      JSONObject entry = set.getJSONObject(i);
      if (typ.equals(entry.get("type"))) {
        return entry.get("id").toString();
      }
    }

    return null; // did not find the requested type; not_found indicated by null
  }

  private JSONArray possible_list(Object o) {
    JSONArray l = null;
    if (o instanceof JSONObject) {
      l = new JSONArray();
      l.put(o);
    } else if (o instanceof JSONArray) {
      l = (JSONArray) o;
    } else {
      System.out.println("Json object is neither an JSONObject nor a JSONArray. Abort.");
      System.exit(-1);
    }
    return l;
  }

  @Override
  public String toString() {
    return this.data.toString(2); // format it with 2 spaces
  }

  private static abstract class SwissProtEntryHandler {
    public abstract void handle(SwissProtEntry entry);
  }
}
