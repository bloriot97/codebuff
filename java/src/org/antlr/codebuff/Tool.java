package org.antlr.codebuff;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Ok, changed requirements. Grammar must have WS on hidden channel and comments skipped or on non-HIDDEN channel */
public class Tool {
	public static boolean showFileNames = false;
	public static boolean showTokens = false;

	public static void main(String[] args)
		throws Exception
	{
		if ( args.length<2 ) {
			System.err.println("ExtractFeatures root-dir-of-samples test-file");
		}
		int tabSize = 4; // TODO: MAKE AN ARGUMENT
		String corpusDir = args[0];
		String testFilename = args[1];
		Corpus corpus = train(corpusDir, JavaLexer.class, JavaParser.class, tabSize);
		InputDocument testDoc = load(testFilename, JavaLexer.class, tabSize);
		String output = format(corpus, testDoc, tabSize);
		System.out.println(output);
	}

	/** Given a corpus, format the document by tokenizing and using the
	 *  corpus to locate newline and whitespace injection points.
	 */
	public static String format(Corpus corpus, InputDocument testDoc, int tabSize)
		throws Exception
	{
		parse(testDoc, JavaLexer.class, JavaParser.class, "compilationUnit");
		Formatter formatter = new Formatter(corpus, testDoc, tabSize);
		String formattedOutput = formatter.format();
		System.out.println("misclassified: "+formatter.misclassified);
		testDoc.tokens.seek(0);
		Token secondToken = testDoc.tokens.LT(2);
		String prefix = testDoc.tokens.getText(Interval.of(0, secondToken.getTokenIndex()));
		return prefix+formattedOutput;
	}

	public static Corpus train(String rootDir,
							   Class<? extends Lexer> lexerClass,
							   Class<? extends Parser> parserClass,
							   int tabSize)
		throws Exception
	{
		List<String> allFiles = getFilenames(new File(rootDir), ".*\\.java");
		List<InputDocument> documents = load(allFiles, lexerClass, tabSize);
		return processSampleDocs(documents, lexerClass, parserClass, tabSize);
	}

	public void saveCSV(List<InputDocument> documents, String dir) throws IOException {
		FileWriter fw = new FileWriter(dir+"/style.csv");
		BufferedWriter bw = new BufferedWriter(fw);
//		bw.write(Utils.join(CollectFeatures.FEATURE_NAMES, ", "));
		bw.write("\n");
		for (InputDocument doc : documents) {
			for (int[] record : doc.featureVectors) {
				String r = join(record, ", ");
				bw.write(r);
				bw.write('\n');
			}
		}
		bw.close();
	}

	public static Corpus processSampleDocs(List<InputDocument> docs,
										   Class<? extends Lexer> lexerClass,
										   Class<? extends Parser> parserClass,
										   int tabSize)
		throws Exception
	{
		List<InputDocument> documents = new ArrayList<>();
		List<int[]> featureVectors = new ArrayList<>();
		List<Integer> injectNewlines = new ArrayList<>();
		List<Integer> injectWS = new ArrayList<>();
		List<Integer> indent = new ArrayList<>();
		List<Integer> levelsToCommonAncestor = new ArrayList<>();
		for (InputDocument doc : docs) {
			if ( showFileNames ) System.out.println(doc);
			process(doc, lexerClass, parserClass, "compilationUnit", tabSize);
			for (int i=0; i<doc.featureVectors.size(); i++) {
				documents.add(doc);
				int[] featureVec = doc.featureVectors.get(i);
				injectNewlines.add(doc.injectNewlines.get(i));
				injectWS.add(doc.injectWS.get(i));
				indent.add(doc.indent.get(i));
				levelsToCommonAncestor.add(doc.levelsToCommonAncestor.get(i));
				featureVectors.add(featureVec);
			}
		}
		System.out.printf("%d feature vectors\n", featureVectors.size());
		return new Corpus(documents, featureVectors, injectNewlines, injectWS, indent, levelsToCommonAncestor);
	}

	/** Parse document, save feature vectors to the doc but return it also */
	public static void process(InputDocument doc,
							   Class<? extends Lexer> lexerClass,
							   Class<? extends Parser> parserClass,
							   String startRuleName,
							   int tabSize)
		throws Exception
	{
		parse(doc, lexerClass, parserClass, startRuleName);

		CollectFeatures collect = new CollectFeatures(doc, tabSize);
		collect.computeFeatureVectors();
//		ParseTreeWalker.DEFAULT.walk(collect, doc.tree);
		doc.featureVectors = collect.getFeatures();
		doc.injectNewlines = collect.getInjectNewlines();
		doc.injectWS = collect.getInjectWS();
		doc.indent = collect.getIndent();
		doc.levelsToCommonAncestor = collect.getLevelsToCommonAncestor();
	}

	public static CommonTokenStream tokenize(String doc, Class<? extends Lexer> lexerClass)
		throws Exception
	{
		ANTLRInputStream input = new ANTLRInputStream(doc);
		Constructor<? extends Lexer> lexerCtor =
			lexerClass.getConstructor(CharStream.class);
		Lexer lexer = lexerCtor.newInstance(input);

		CommonTokenStream tokens = new CommonTokenStream(lexer);
		tokens.fill();
		return tokens;
	}

	/** Parse doc and fill tree and tokens fields */
	public static void parse(InputDocument doc,
							 Class<? extends Lexer> lexerClass,
							 Class<? extends Parser> parserClass,
							 String startRuleName)
		throws Exception
	{
		ANTLRInputStream input = new ANTLRInputStream(doc.content);
		Constructor<? extends Lexer> lexerCtor =
			lexerClass.getConstructor(CharStream.class);
		Lexer lexer = lexerCtor.newInstance(input);
		input.name = doc.fileName;

		Constructor<? extends Parser> parserCtor =
			parserClass.getConstructor(TokenStream.class);
		CommonTokenStream tokens = new CommonTokenStream(lexer);

		if ( showTokens ) {
			tokens.fill();
			for (Object tok : tokens.getTokens()) {
				System.out.println(tok);
			}
		}

		Parser parser = parserCtor.newInstance(tokens);
		parser.setBuildParseTree(true);
		Method startRule = parserClass.getMethod(startRuleName);
		ParserRuleContext tree = (ParserRuleContext)startRule.invoke(parser, (Object[]) null);

		doc.tokens = tokens;
		doc.tree = tree;
	}

	/** Get all file contents into input array */
	public static List<InputDocument> load(List<String> fileNames,
										   Class<? extends Lexer> lexerClass,
										   int tabSize)
		throws Exception
	{
		List<InputDocument> input = new ArrayList<InputDocument>(fileNames.size());
		int i = 0;
		for (String f : fileNames) {
			InputDocument doc = load(f, lexerClass, tabSize);
			doc.index = i++;
			input.add(doc);
		}
		System.out.println(input.size()+" files");
		return input;
	}

	public static InputDocument load(String fileName,
									 Class<? extends Lexer> lexerClass,
									 int tabSize)
		throws Exception
	{
		Path path = FileSystems.getDefault().getPath(fileName);
		byte[] filearray = Files.readAllBytes(path);
		String content = new String(filearray);
		String notabs = expandTabs(content, tabSize);
		CommonTokenStream tokens = tokenize(notabs, lexerClass);
		// delete any whitespace on a line by itself, including the newline
		// most likely left over from a comment skipped by lexer
		StringBuilder buf = new StringBuilder();
		int i=0;
		while ( i<tokens.size()-1 ) {
			Token t = tokens.get(i);
			buf.append(t.getText());
			// if we see whitespace followed by whitespace, it must have been
			// split up by a comment or other skipped token. Assume we want to
			// delete the 2nd one.
			// "\n    " then "   " should become "\n    "
			// "\n\n    " then "   " should become "\n\n    "
			if ( t.getText().matches("\n+ +") ) {
				Token next = tokens.get(i+1);
				if ( next.getText().matches("\n +") ) {
					// delete by bumping i so we don't see next in next iteration
					i++;
				}
			}
			i++;
		}

		return new InputDocument(fileName, buf.toString());
	}

	public static List<String> getFilenames(File f, String inputFilePattern) throws Exception {
		List<String> files = new ArrayList<String>();
		getFilenames_(f, inputFilePattern, files);
		return files;
	}

	public static void getFilenames_(File f, String inputFilePattern, List<String> files) throws Exception {
		// If this is a directory, walk each file/dir in that directory
		if (f.isDirectory()) {
			String flist[] = f.list();
			for (int i=0; i < flist.length; i++) {
				getFilenames_(new File(f, flist[i]), inputFilePattern, files);
			}
		}

		// otherwise, if this is an input file, load it!
		else if ( inputFilePattern==null || f.getName().matches(inputFilePattern) ) {
			files.add(f.getAbsolutePath());
		}
	}

	public static String join(int[] array, String separator) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < array.length; i++) {
			builder.append(array[i]);
			if (i < array.length - 1) {
				builder.append(separator);
			}
		}

		return builder.toString();
	}

	public static void wipeLineAndPositionInfo(CommonTokenStream tokens) {
		tokens.fill();
		for (Token t : tokens.getTokens()) {
			CommonToken ct = (CommonToken)t;
			ct.setLine(0);
			ct.setCharPositionInLine(-1);
		}
	}

	public static List<CommonToken> copy(CommonTokenStream tokens) {
		List<CommonToken> copy = new ArrayList<>();
		tokens.fill();
		for (Token t : tokens.getTokens()) {
			CommonToken ct = (CommonToken)t;
			copy.add(new CommonToken(t));
		}
		return copy;
	}

	public static int L0_Distance(boolean[] categorical, int[] A, int[] B) {
		int count = 0; // count how many mismatched categories there are
		for (int i=0; i<A.length; i++) {
			if ( categorical[i] ) {
				if ( A[i] != B[i] ) {
					count++;
				}
			}
		}
		return count;
	}

	/** A distance of 0 should count much more than non-0. Also, penalize
	 *  mismatches closer to current token than those farther away.
	 */
	public static int weightedL0_Distance(FeatureMetaData[] featureTypes, int[] A, int[] B) {
		int count = 0; // count how many mismatched categories there are
		for (int i=0; i<A.length; i++) {
			if ( featureTypes[i].type==FeatureType.TOKEN ||
				 featureTypes[i].type==FeatureType.RULE )
			{
				if ( A[i] != B[i] ) {
					count += featureTypes[i].mismatchCost;
				}
			}
		}
		return count;
	}

	public static int max(List<Integer> Y) {
		int max = 0;
		for (int y : Y) max = Math.max(max, y);
		return max;
	}

	public static int sum(int[] a) {
		int s = 0;
		for (int x : a) s += x;
		return s;
	}

//	// From https://en.wikipedia.org/wiki/Levenshtein_distance
//	public static int LevenshteinDistance(String s, String t) {
//		return LevenshteinDistance(s, s.length(), t, t.length());
//	}
//
//	public static int LevenshteinDistance(String s, int slen, String t, int tlen) {
//		int cost;
//
//		// base case: empty strings
//		if (slen == 0) return tlen;
//		if (tlen == 0) return slen;
//
//		// test if last characters of the strings match
//		if ( s.charAt(slen-1) == t.charAt(tlen-1) ) {
//			cost = 0;
//		}
//		else {
//			cost = 1;
//		}
//
//		// return minimum of delete char from s, delete char from t, and delete char from both
//		return
//			Math.min(
//				Math.min(LevenshteinDistance(s, slen - 1, t, tlen    ) + 1,
//						 LevenshteinDistance(s, slen    , t, tlen - 1) + 1),
//				LevenshteinDistance(s, slen - 1, t, tlen - 1) + cost);
//	}

	// from https://en.wikipedia.org/wiki/Levenshtein_distance
	public static int levenshteinDistance(String s, String t) {
	    // degenerate cases
	    if (s.equals(t)) return 0;
	    if (s.length() == 0) return t.length();
	    if (t.length() == 0) return s.length();

	    // create two work vectors of integer distances
	    int[] v0 = new int[t.length() + 1];
	    int[] v1 = new int[t.length() + 1];

	    // initialize v0 (the previous row of distances)
	    // this row is A[0][i]: edit distance for an empty s
	    // the distance is just the number of characters to delete from t
	    for (int i = 0; i < v0.length; i++) {
			v0[i] = i;
		}

	    for (int i = 0; i < s.length(); i++) {
	        // calculate v1 (current row distances) from the previous row v0

	        // first element of v1 is A[i+1][0]
	        //   edit distance is delete (i+1) chars from s to match empty t
	        v1[0] = i + 1;

	        // use formula to fill in the rest of the row
	        for (int j = 0; j < t.length(); j++)
	        {
	            int cost = s.charAt(i) == t.charAt(j) ? 0 : 1;
	            v1[j + 1] = Math.min(
								Math.min(v1[j] + 1, v0[j + 1] + 1),
								v0[j] + cost);
	        }

	        // copy v1 (current row) to v0 (previous row) for next iteration
			System.arraycopy(v1, 0, v0, 0, v0.length);
	    }

	    return v1[t.length()];
	}

	/* Compare whitespace and give an approximate Levenshtein distance /
	   edit distance. MUCH faster to use this than pure Levenshtein which
	   must consider all of the "real" text that is in common.

		when only 1 kind of char, just substract lengths
		Orig    Altered Distance
		AB      A B     1
		AB      A  B    2
		AB      A   B   3
		A B     A  B    1

		A B     AB      1
		A  B    AB      2
		A   B   AB      3

		when ' ' and '\n', we count separately.

		A\nB    A B     spaces delta=1, newline delete=1, distance = 2
		A\nB    A  B    spaces delta=2, newline delete=1, distance = 3
		A\n\nB  A B     spaces delta=1, newline delete=2, distance = 3
		A\n \nB A B     spaces delta=0, newline delete=2, distance = 2
		A\n \nB A\nB    spaces delta=1, newline delete=1, distance = 2
		A \nB   A\n B   spaces delta=0, newline delete=0, distance = 0
						levenshtein would count this as 2 I think but
						for our doc distance, I think it's ok to measure as same
	 */
//	public static int editDistance(String s, String t) {
//	}

	/*
			A \nB   A\n B   spaces delta=0, newline delete=0, distance = 0
						levenshtein would count this as 2 I think but
						for our doc distance, I think it's ok to measure as same
	 */
	public static int whitespaceEditDistance(String s, String t) {
		int s_spaces = count(s, ' ');
		int s_nls = count(s, '\n');
		int t_spaces = count(t, ' ');
		int t_nls = count(t, '\n');
		return Math.abs(s_spaces - t_spaces) + Math.abs(s_nls - t_nls);
	}

	/** Compute a document difference metric 0-1.0 between two documents that
	 *  are identical other than (likely) the whitespace and comments.
	 *
	 *  1.0 means the docs are maximally different and 0 means docs are identical.
	 *
	 *  The Levenshtein distance between the docs counts only
	 *  whitespace diffs as the non-WS content is identical.
	 *  Levenshtein distance is bounded by 0..max(len(doc1),len(doc2)) so
	 *  we normalize the distance by dividing by max WS count.
	 *
	 *  TODO: can we simplify this to a simple walk with two
	 *  cursors through the original vs formatted counting
	 *  mismatched whitespace? real text are like anchors.
	 */
	public static double docDiff(String original,
	                             String formatted,
	                             Class<? extends Lexer> lexerClass)
		throws Exception
	{
		// Grammar must strip all but real tokens and whitespace (and put that on hidden channel)
		CommonTokenStream original_tokens = tokenize(original, lexerClass);
//		String s = original_tokens.getText();
		CommonTokenStream formatted_tokens = tokenize(formatted, lexerClass);
//		String t = formatted_tokens.getText();

		// walk token streams and examine whitespace in between tokens
		int i = 1;
		int ws_distance = 0;
		int original_ws = 0;
		int formatted_ws = 0;
		while ( true ) {
			Token ot = original_tokens.LT(i);
			if ( ot==null || ot.getType()==Token.EOF ) break;
			List<Token> ows = original_tokens.getHiddenTokensToLeft(ot.getTokenIndex());
			original_ws += tokenText(ows).length();

			Token ft = formatted_tokens.LT(i);
			if ( ft==null || ft.getType()==Token.EOF ) break;
			List<Token> fws = formatted_tokens.getHiddenTokensToLeft(ft.getTokenIndex());
			formatted_ws += tokenText(fws).length();

			ws_distance += whitespaceEditDistance(tokenText(ows), tokenText(fws));
			i++;
		}
		// it's probably ok to ignore ws diffs after last real token

//		int non_ws = 0;
//		for (Token tok : original_tokens.getTokens()) {
//			if ( tok.getType()!=Token.EOF && tok.getChannel()==Lexer.DEFAULT_TOKEN_CHANNEL ) {
//				non_ws += tok.getText().length();
//			}
//		}
//		String original_text_with_ws = original_tokens.getText();
//		int original_ws = original_text_with_ws.length() - non_ws;
//		int formatted_ws = formatted.length() - non_ws;
//		int ws_distance = Tool.levenshteinDistance(original_text_with_ws, formatted);
		int max_ws = Math.max(original_ws, formatted_ws);
		double normalized_ws_distance = ((float) ws_distance)/max_ws;
		return normalized_ws_distance;
	}

	public static String tokenText(List<Token> tokens) {
		if ( tokens==null ) return "";
		StringBuilder buf = new StringBuilder();
		for (Token t : tokens) {
			buf.append(t.getText());
		}
		return buf.toString();
	}

	public static int getNumberRealTokens(CommonTokenStream tokens, int from, int to) {
		if ( tokens==null ) return 0;
		int n = 0;
		if ( from<0 ) from = 0;
		if ( to>tokens.size() ) to = tokens.size()-1;
		for (int i = from; i <= to; i++) {
			Token t = tokens.get(i);
			if ( t.getChannel()==Token.DEFAULT_CHANNEL ) {
				n++;
			}
		}
		return n;
	}

	public static String spaces(int n) {
		return sequence(n, " ");
//		StringBuilder buf = new StringBuilder();
//		for (int sp=1; sp<=n; sp++) buf.append(" ");
//		return buf.toString();
	}

	public static String newlines(int n) {
		return sequence(n, "\n");
//		StringBuilder buf = new StringBuilder();
//		for (int sp=1; sp<=n; sp++) buf.append("\n");
//		return buf.toString();
	}

	public static String sequence(int n, String s) {
		StringBuilder buf = new StringBuilder();
		for (int sp=1; sp<=n; sp++) buf.append(s);
		return buf.toString();
	}

	public static int count(String s, char x) {
		int n = 0;
		for (int i = 0; i<s.length(); i++) {
			if ( s.charAt(i)==x ) {
				n++;
			}
		}
		return n;
	}

	public static String expandTabs(String s, int tabSize) {
		if ( s==null ) return null;
		StringBuilder buf = new StringBuilder();
		int col = 0;
		for (int i = 0; i<s.length(); i++) {
			char c = s.charAt(i);
			switch ( c ) {
				case '\n' :
					col = 0;
					buf.append(c);
					break;
				case '\t' :
					buf.append(spaces(tabSize - col % tabSize));
					break;
				default :
					col++;
					buf.append(c);
					break;
			}
		}
		return buf.toString();
	}

//	public static class Foo {
//		public static void main(String[] args) {
//			String s =
//			"                            noSuchAttributeReported = true;\n"+
//			"                            errMgr.runTimeError(this, scope,\n"+
//			"                                                ErrorType.NO_SUCH_ATTRIBUTE,\n"+
//			"                                                argument.getKey());\n";
//			System.out.println(expandTabs(s, 4));
//		}
//	}
}
