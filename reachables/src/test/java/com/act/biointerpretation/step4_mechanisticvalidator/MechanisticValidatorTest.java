package com.act.biointerpretation.step4_mechanisticvalidator;

import act.server.NoSQLAPI;
import act.shared.Reaction;
import chemaxon.formats.MolExporter;
import chemaxon.formats.MolImporter;
import chemaxon.reaction.ReactionException;
import chemaxon.reaction.Reactor;
import chemaxon.struc.Molecule;
import com.act.biointerpretation.Utils.ReactionProjector;
import com.act.biointerpretation.step2_desalting.ReactionDesalter;
import com.act.biointerpretation.step3_cofactorremoval.CofactorRemover;
import com.act.biointerpretation.test.util.MockedNoSQLAPI;
import com.act.biointerpretation.test.util.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class MechanisticValidatorTest {

  TestUtils utilsObject;

  @Before
  public void setUp() throws Exception {
    // In case we ever use Mockito annotations, don't forget to initialize them.
    MockitoAnnotations.initMocks(ReactionDesalter.class);
    utilsObject = new TestUtils();
  }

  @Test
  public void testROTransformation() throws Exception {
    Reactor reactor = new Reactor();
    reactor.setReactionString("[H][#6:2]-[#8:1][H]>>[#6:2]=[O:1]");

    Molecule one = MolImporter.importMol("InChI=1S/C21H27N7O14P2/c22-17-12-19(25-7-24-17)28(8-26-12)21-16(32)14(30)11(41-21)6-39-44(36,37)42-43(34,35)38-5-10-13(29)15(31)20(40-10)27-3-1-2-9(4-27)18(23)33/h1-4,7-8,10-11,13-16,20-21,29-32H,5-6H2,(H5-,22,23,24,25,33,34,35,36,37)/p+1/t10-,11-,13-,14-,15-,16-,20-,21-/m1/s1");
    Molecule two = MolImporter.importMol("InChI=1S/C5H12O/c1-3-4-5(2)6/h5-6H,3-4H2,1-2H3/t5-/m1/s1");

    reactor.setReactants(new Molecule[] {one, two});
    Molecule[] products = reactor.react();
    System.out.println(products.length);
    System.out.println(MolExporter.exportToFormat(products[0], "inchi"));
  }

  @Test
  public void test2() throws Exception {
    List<Reaction> testReactions = new ArrayList<>();

    Long[] products = {4L};

    Map<Long, String> idToInchi = new HashMap<>();

    // The first inchi is a cofactor while the second is not.
    idToInchi.put(1L, "InChI=1/C10H15N4O15P3/c15-5-3(1-26-31(22,23)29-32(24,25)28-30(19,20)21)27-9(6(5)16)14-2-11-4-7(14)12-10(18)13-8(4)17/h2-3,5-6,9,15-16H,1H2,(H,22,23)(H,24,25)(H2,19,20,21)(H2,12,13,17,18)");
    idToInchi.put(2L, "InChI=1S/CH2O2/c2-1-3/h1H,(H,2,3)/p-1");
    idToInchi.put(3L, "InChI=1S/C7H5Cl3/c8-4-5-2-1-3-6(9)7(5)10/h1-3H,4H2");

    Long[] substrates1 = {1L, 3L};
    Long[] substrates2 = {2L};

    Integer[] substrateCoefficients1 = {2, 3};
    Integer[] substrateCoefficients2 = {2};
    Integer[] productCoefficients = {3};

    Reaction testReaction1 =
        utilsObject.makeTestReaction(substrates1, products, substrateCoefficients1, productCoefficients, true);

    Reaction testReaction2 =
        utilsObject.makeTestReaction(substrates2, products, substrateCoefficients2, productCoefficients, true);

    testReactions.add(testReaction1);
    testReactions.add(testReaction2);

    MockedNoSQLAPI mockAPI = new MockedNoSQLAPI();
    mockAPI.installMocks(testReactions, utilsObject.SEQUENCES, utilsObject.ORGANISM_NAMES, idToInchi);

    NoSQLAPI mockNoSQLAPI = mockAPI.getMockNoSQLAPI();
  }

}
