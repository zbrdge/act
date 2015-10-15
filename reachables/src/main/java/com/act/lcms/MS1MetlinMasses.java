package com.act.lcms;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;
import java.util.Comparator;
import java.io.IOException;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import org.apache.commons.lang3.tuple.Pair;

public class MS1MetlinMasses {

  class YZ {
    Double mz;
    Double intensity;

    public YZ(Double mz, Double intensity) {
      this.mz = mz;
      this.intensity = intensity;
    }
  }

  class XZ {
    Double time;
    Double intensity;

    public XZ(Double t, Double i) {
      this.time = t;
      this.intensity = i;
    }
  }

  // In the MS1 case, we look for a very tight window 
  // because we do not noise to broaden our signal
  final static Double MS1_MZ_TOLERANCE = 0.001;

  // when aggregating the MS1 signal, we do not expect
  // more than these number of measurements within the
  // mz window specified by the tolerance above
  static final Integer MAX_MZ_IN_WINDOW = 3;

  private double extractMZ(double mzWanted, List<Pair<Double, Double>> intensities) {
    double intensityFound = 0;
    int numWithinPrecision = 0;
    double mzLowRange = mzWanted - MS1_MZ_TOLERANCE;
    double mzHighRange = mzWanted + MS1_MZ_TOLERANCE;
    // we expect there to be pretty much only one intensity value in the precision
    // range we are looking at. But if a lot of masses show up then complain
    for (Pair<Double, Double> mz_int : intensities) {
      double mz = mz_int.getLeft();
      double intensity = mz_int.getRight();

      if (mz >= mzLowRange && mz <= mzHighRange) {
        intensityFound += intensity;
        numWithinPrecision++;
      }
    }

    if (numWithinPrecision > MAX_MZ_IN_WINDOW) {
      System.out.format("Only expected %d, but found %d in the mz range [%f, %f]\n", MAX_MZ_IN_WINDOW, 
          numWithinPrecision, mzLowRange, mzHighRange);
    }

    return intensityFound;
  }

  private Map<String, List<XZ>> getMS1(Map<String, Double> metlinMasses, String ms1File) throws Exception {
    return getMS1(metlinMasses, new LCMSNetCDFParser().getIterator(ms1File));
  }

  private Map<String, List<XZ>> getMS1(Map<String, Double> metlinMasses, Iterator<LCMSSpectrum> ms1File) {

    // create the map with placeholder empty lists for each ion
    // we will populate this later when we go through each timepoint
    Map<String, List<XZ>> ms1AtVariousMasses = new HashMap<>();
    for (String ionDesc : metlinMasses.keySet()) {
      List<XZ> ms1 = new ArrayList<>();
      ms1AtVariousMasses.put(ionDesc, ms1);
    }

    while (ms1File.hasNext()) {
      LCMSSpectrum timepoint = ms1File.next();

      // get all (mz, intensity) at this timepoint
      List<Pair<Double, Double>> intensities = timepoint.getIntensities();

      // for this timepoint, extract each of the ion masses from the METLIN set
      for (Map.Entry<String, Double> metlinMass : metlinMasses.entrySet()) {
        String ionDesc = metlinMass.getKey();
        Double ionMz = metlinMass.getValue();

        // this time point is valid to look at if its max intensity is around
        // the mass we care about. So lets first get the max peak location
        double intensityForMz = extractMZ(ionMz, intensities);

        // the above is Pair(mz_extracted, intensity), where mz_extracted = mz
        // we now add the timepoint val and the intensity to the output
        XZ intensityAtThisTime = new XZ(timepoint.getTimeVal(), intensityForMz);
        ms1AtVariousMasses.get(ionDesc).add(intensityAtThisTime);
      }
    }

    return ms1AtVariousMasses;
  }

  class MetlinIonMass {
    // colums in each row from METLIN data, as seen here: 
    // https://metlin.scripps.edu/mz_calc.php?mass=300.120902994

    String mode; // pos or neg
    String name; // M+H, M+K, etc
    Integer charge;
    Double mz;

    MetlinIonMass(String mode, String name, Integer charge, Double mz) {
      this.mode = mode; this.name = name; this.charge = charge; this.mz = mz;
    }
  }

  private List<MetlinIonMass> queryMetlin(Double mz) throws IOException {
    String query = "https://metlin.scripps.edu/mz_calc.php?mass=" + mz;
    
    URL metlin = new URL(query);
    BufferedReader in = new BufferedReader(new InputStreamReader(metlin.openStream()));

    String prefix = "<table border=1>";
    String suffix = "</table>";
    String ln, dataLine = null;
    while ((ln = in.readLine()) != null) {
      if (!ln.startsWith(prefix))
        continue;
      dataLine = ln; 
      if (!dataLine.endsWith(suffix)) {
        dataLine = null;
      } else {
        // remove the prefix and suffix
        dataLine = dataLine.substring(prefix.length(), dataLine.length() - suffix.length());
      }
      break;
    }
    in.close();

    // split dataLine into rows of the table
    List<MetlinIonMass> rows = new ArrayList<>();
    String[] rs = dataLine.split("</tr>");
    // we iterate from [1:n] assuming the first row is the `th` row with 
    // column headers MODE NAME CHARGE m/z
    for (int i = 1; i < rs.length; i++) {
      // remove the row opening tag (the closing tag already removed by split
      String r = rs[i].substring("<tr>".length());
      String[] rowspl = r.split("</td>");
      List<String> row = new ArrayList<>();
      for (String td : rowspl) {
        // remove some HTML junk that is present
        td = td.replace("&nbsp;", ""); 
        td = td.replace(" align=left", "");
        row.add(td.substring("<td>".length()));
      }
      System.out.println("ROW: " + row);
      if (row.size() != 4)
        throw new RuntimeException("Table format unexpected. Expecting 4 col row but recvd: " + r);

      String mode = row.get(0);
      String name = row.get(1);
      Integer charge = Integer.parseInt(row.get(2));
      Double rowMz = Double.parseDouble(row.get(3));
      rows.add(new MetlinIonMass(mode, name, charge, rowMz));
    }

    return rows;
  }

  private Map<String, Double> scrapeMETLINForMainMass(Double mz, String ionMode) throws IOException {
    List<MetlinIonMass> rows = queryMetlin(mz);
    Map<String, Double> scraped = new HashMap<>();
    for (MetlinIonMass metlinMass : rows) {
      scraped.put(metlinMass.name, metlinMass.mz);
    }
    return scraped;
  }

  private static boolean areNCFiles(String[] fnames) {
    for (String n : fnames) {
      System.out.println(".nc file = " + n);
      if (!n.endsWith(".nc"))
        return false;
    }
    return true;
  }

  private void plot(Map<String, List<XZ>> ms1s, Map<String, Double> metlinMzs, String outPrefix, String fmt) 
    throws IOException {

    String outImg = outPrefix + "." + fmt;
    String outData = outPrefix + ".data";

    // Write data output to outfile
    PrintStream out = new PrintStream(new FileOutputStream(outData));

    int count = 0;
    List<String> plotID = new ArrayList<>(ms1s.size());
    for (Map.Entry<String, List<XZ>> ms1ForIon : ms1s.entrySet()) {
      String ion = ms1ForIon.getKey();
      List<XZ> ms1 = ms1ForIon.getValue();

      plotID.add(String.format("ion: %s, mz: %.5f", ion, metlinMzs.get(ion)));
      // print out the spectra to outDATA
      for (XZ xz : ms1) {
        out.format("%.4f\t%.4f\n", xz.time, xz.intensity);
        out.flush();
      }
      // delimit this dataset from the rest
      out.print("\n\n");
    }

    // close the .data
    out.close();

    // render outDATA to outPDF using gnuplot
    // 105.0 here means 105% for the y-range of a [0%:100%] plot. We want to leave some buffer space at
    // at the top, and hence we go a little outside of the 100% max range.
    new Gnuplotter().plot2D(outData, outImg, plotID.toArray(new String[plotID.size()]), "time", null, "intensity",
        fmt);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 4 || !areNCFiles(new String[] {args[3]})) {
      throw new RuntimeException("Needs: \n" + 
          "(1) mz for main product, e.g., 431.1341983 (ononin) \n" +
          "(2) ion mode = pos OR neg \n" +
          "(3) prefix for .data and rendered .pdf \n" +
          "(4) NetCDF .nc file 01.nc from MS1 run \n"
          );
    }

    String fmt = "pdf";
    Double mz = Double.parseDouble(args[0]);
    String ionMode = args[1];
    String outPrefix = args[2];
    String ms1File = args[3];

    MS1MetlinMasses c = new MS1MetlinMasses();
    Map<String, Double> metlinMasses = c.scrapeMETLINForMainMass(mz, ionMode);
    Map<String, List<XZ>> ms1s = c.getMS1(metlinMasses, ms1File);
    c.plot(ms1s, metlinMasses, outPrefix, fmt);

  }
}
