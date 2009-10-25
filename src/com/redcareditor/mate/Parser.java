package com.redcareditor.mate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.events.VerifyListener;

import com.redcareditor.mate.document.MateDocument;
import com.redcareditor.onig.Match;
import com.redcareditor.onig.Range;
import com.redcareditor.onig.Rx;

public class Parser {
	public Grammar grammar;
	public MateText mateText;
	public StyledText styledText;
	public MateDocument document;
	
	public int parsed_upto;	
	public int lookAhead;
	public int lastVisibleLine;
	public int deactivationLevel;
	public int parsedUpto;
	public boolean alwaysParseAll;
	
	public RangeSet changes;
	public Scope root;
	
	// temporary stores for the modifications to the mateText
	private int modifyStartOffset; 
	private int modifyEndOffset;
	private int modifyStartLineOffset; 
	private int modifyEndLineOffset;
	private int modifyStartLine; 
	private int modifyEndLine;
	private int modifyNumLines;
	private int modifyOldNumLines;
	private int modifyPrevLines;
	private String[] modifyLines;
	private String modifyText;
	
	public Parser(Grammar g, MateText m) {
		g.initForUse();
		grammar = g;
		mateText = m;
		styledText = m.getTextWidget();
		lookAhead = 100;
		lastVisibleLine = 0;
//		tags = new Sequence<TextTag>(null);
		changes = new RangeSet();
		deactivationLevel = 0;
		makeRoot();
		attachListeners();
		parsedUpto = 0;
		alwaysParseAll = false;
		document = m.getMateDocument();
	}
	
	public void makeRoot() {
		this.root = new Scope(mateText, this.grammar.scopeName);
		this.root.isOpen = true;
//		this.root.setStartPos(0, 0, true);
//		int lineIx = styledText.getLineCount()-1;
//		this.root.setEndPos(lineIx, 
//							styledText.getCharCount() - styledText.getOffsetAtLine(lineIx), false);
//		System.out.printf("making root: %s\n", this.grammar.scopeName);
		DoublePattern dp = new DoublePattern();
		dp.name = this.grammar.name;
		dp.patterns = this.grammar.patterns;
		dp.grammar = this.grammar;
		this.root.pattern = dp;
	}
	
	public void attachListeners() {
		mateText.getTextWidget().addVerifyListener(new VerifyListener() {
			public void verifyText(VerifyEvent e) {
				verifyEventCallback(e.start, e.end, e.text);
			}
		});
		
		mateText.getTextWidget().addModifyListener(new ModifyListener() {
			public void modifyText(ModifyEvent e) {
				modifyEventCallback();
			}
		});
	}

	public void verifyEventCallback(int start, int end, String text) {
		System.out.printf("verifyEventCallback(start:%d, end:%d)\n", start, end);
		modifyStartOffset = start;
		modifyEndOffset   = end;
		modifyText        = text;
		modifyStartLine   = styledText.getLineAtOffset(start);
		modifyEndLine     = styledText.getLineAtOffset(end);
		modifyStartLineOffset   = modifyStartOffset - styledText.getOffsetAtLine(modifyStartLine);
		modifyEndLineOffset     = modifyEndOffset   - styledText.getOffsetAtLine(modifyEndLine);
		modifyLines = modifyText.split("\n");
		modifyNumLines    = modifyLines.length - 1;
		String oldText;
		if (start - end > 0)
			oldText = styledText.getText(start, end);
		else
			oldText = "";
		modifyOldNumLines = oldText.split("\n").length;
		System.out.printf(" modifyStartLine: %d\n modifyEndLine: %d\n modifyStartOffset: %d\n modifyEndOffset: %d\n modifyNumLines: %d\n modifyOldNumLines: %d\n",
						modifyStartLine, modifyEndLine, modifyStartOffset, modifyEndOffset, modifyNumLines, modifyOldNumLines);
		System.out.printf(" modifyStartLineOffset: %d\n", modifyStartLineOffset);
		System.out.printf(" modifyEndLineOffset: %d\n", modifyEndLineOffset);
		parseRange(modifyStartLine, modifyStartLine + modifyNumLines);
	}
	
	public void modifyEventCallback() {
		// TODO: this isn't quite right...
		// changes.add(styledText.getLineAtOffset(modifyStart), 
		// 		styledText.getLineAtOffset(modifyStart + modifyText.length()));
//		System.out.printf("modifying %d - %d, %d, %s\n", modifyStart, modifyEnd, styledText.getLineAtOffset(modifyStart), modifyText);
		// processChanges();
	}

	// Process all change ranges.
	public void processChanges() {
		int thisParsedUpto = -1;
		System.out.printf("process_changes (lastVisibleLine: %d) (charCount = %d)\n", lastVisibleLine, styledText.getCharCount());
		for (Range range : changes) {
			if (range.end > thisParsedUpto && range.start <= lastVisibleLine + lookAhead) {
				int rangeEnd = Math.min(lastVisibleLine + lookAhead, range.end);
				thisParsedUpto = parseRange(range.start, rangeEnd);
			}
		}
//		System.out.printf("%s\n", root.pretty(0));
		changes.ranges.clear();
	}

	// Parse from from_line to *at least* to_line. Will parse
	// more if necessary. Returns the index of the last line
	// parsed.
	private int parseRange(int fromLine, int toLine) {
		System.out.printf("parse_range(%d, %d)\n", fromLine, toLine);
		int lineIx = fromLine;
		boolean scopeChanged = false;
		boolean scopeEverChanged = false;
		int endLine = Math.min(lastVisibleLine + 100, styledText.getLineCount() - 1);
		while (lineIx <= toLine || scopeEverChanged && lineIx <= endLine) {
			scopeChanged = parseLine(lineIx++);
			if (scopeChanged) {
				scopeEverChanged = true;
				// In the old scheme this wasn't necessary because 
				// the scope_at used a simple scan from the front. The GSequences
				// on the other hand can get confused if the later scopes
				// are inconsistent with earler ones. So we have to clear everything.
				// TODO: figure out a way to OPTIMIZE this again.
				root.clearAfter(lineIx, -1);
				removeColourAfter(lineIx, 0);
				this.parsedUpto = lineIx;
			}
			// stdout.printf("parse_line returned: %s\n", scope_changed ? "true" : "false");
			//stdout.printf("pretty:\n%s\n", root.pretty(2));
		}
		return toLine;
	}
	
	public void sleep(int ms) {
		try{
		  //do what you want to do before sleeping
		  Thread.currentThread().sleep(ms);//sleep for 1000 ms
		  //do what you want to do after sleeptig
		}
		catch(InterruptedException ie){
		//If this thread was intrrupted by
		}
	}
	
	private Scope scopeBeforeStartOfLine(int lineIx) {
		Scope startScope = this.root.scopeAt(lineIx, 0);
		if (startScope.getStart().getLine() == lineIx) {
//			System.out.printf("sbsol: %s\n", startScope.pattern.name);
			startScope = startScope.containingDoubleScope(lineIx);
		}

		return startScope;
	}

	private Scope scopeAfterEndOfLine(int lineIx, int lineLength) {
		Scope endScope = this.root.scopeAt(lineIx, lineLength - 1);
		if (endScope.getStart().getLine() == lineIx ) {
			endScope = endScope.containingDoubleScope(lineIx);
		}

		return endScope;
	}
	
	// Returns the line at the given line index. Uses the modify* private members
	// to determine what the document would look like after the modifications, and 
	// returns that instead of what's currently in the buffer.
	private String getLine(int lineIx) {
		if (lineIx < modifyStartLine)
			return styledText.getLine(lineIx) + "\n";
		else if (lineIx >= modifyStartLine + modifyNumLines + 1)
			return styledText.getLine(lineIx + modifyOldNumLines - modifyNumLines) + "\n";
		else
			return specialGetLine(lineIx);
	}
	
	private String specialGetLine(int lineIx) {
		int lineIndexWithinChange = lineIx - modifyStartLine;
		if (lineIndexWithinChange > 0 && lineIndexWithinChange < modifyNumLines)
			return modifyText.split("\n")[lineIndexWithinChange] + "\n";
		else if (lineIndexWithinChange == 0 && modifyNumLines == 0) {
			String oldLine = styledText.getLine(lineIx + modifyOldNumLines - modifyNumLines - 1);
			System.out.printf("    oldLine: '%s'\n", oldLine);
			String remainingStartChunk = oldLine.substring(0, modifyStartLineOffset);
			String remainingEndChunk = oldLine.substring(modifyEndLineOffset, oldLine.length());
			return remainingStartChunk + modifyLines[modifyLines.length - 1] + remainingEndChunk + "\n";
		}
		else if (lineIndexWithinChange == 0)
			return styledText.getLine(lineIx).substring(0, modifyStartLineOffset) + modifyLines[0] + "\n";
		else if (lineIndexWithinChange == modifyNumLines) {
			String oldLine = styledText.getLine(lineIx + modifyOldNumLines - modifyNumLines);
			String remainingChunk = oldLine.substring(modifyEndLineOffset, oldLine.length());
			return modifyLines[modifyLines.length - 1] + remainingChunk + "\n";
		}
		else {
			System.out.printf("specialGetLine: shouldn't be here");
			return "";
		}
	}
	
	private boolean parseLine(int lineIx) {
		String line = getLine(lineIx);
		System.out.printf("getLine(%d): \"%s\"", lineIx, line);
		int length = line.length();
		System.out.printf("p%d, ", lineIx);
		if (lineIx > this.parsedUpto)
			this.parsedUpto = lineIx;
		Scope startScope = scopeBeforeStartOfLine(lineIx);
		Scope endScope1  = scopeAfterEndOfLine(lineIx, length);
//		System.out.printf("startScope is: %s\n", startScope.name);
//		System.out.printf("endScope1: %s\n", endScope1.name);
		Scanner scanner = new Scanner(startScope, line);
		ArrayList<Scope> allScopes = new ArrayList<Scope>();
		allScopes.add(startScope);
		ArrayList<Scope> closedScopes = new ArrayList<Scope>();
		ArrayList<Scope> removedScopes = new ArrayList<Scope>();
		allScopes.add(startScope);
		for (Marker m : scanner) {
			Scope expectedScope = getExpectedScope(scanner.getCurrentScope(), lineIx, length, scanner.position);
//			if (expectedScope != null)
//				System.out.printf("expectedScope: %s (%d, %d)\n", expectedScope.name, expectedScope.getStart().getLine(), 
//					           expectedScope.getStart().getLineOffset());
//			else
//				System.out.printf("no expected scope\n");
//			System.out.printf("  scope: %s (%d, %d) (line length: %d)\n", 
//								m.pattern.name, m.from, m.match.getCapture(0).end, length);
			if (m.isCloseScope) {
//				System.out.printf("     (closing)\n");
				closeScope(scanner, expectedScope, lineIx, line, length, m, 
							allScopes, closedScopes, removedScopes);
			}
			else if (m.pattern instanceof DoublePattern) {
//				System.out.printf("     (opening)\n");
				openScope(scanner, expectedScope, lineIx, line, length, m, 
						   allScopes, closedScopes, removedScopes);
			}
			else {
//				System.out.printf("     (single)\n");
				singleScope(scanner, expectedScope, lineIx, line, length, m, 
							 allScopes, closedScopes, removedScopes);
			}
			//System.out.printf("pretty:\n%s\n", root.pretty(2));
			scanner.position = m.match.getCapture(0).end;
		}
		clearLine(lineIx, startScope, allScopes, closedScopes, removedScopes);
		Scope endScope2 = scopeAfterEndOfLine(lineIx, length);
//		System.out.printf("end_scope2: %s\n", endScope2.name);
//		System.out.printf("%s\n", this.root.pretty(0));
//		if (colourer != null) {
//			// System.out.printf("before_uncolour_scopes\n");
//			colourer.uncolourScopes(removedScopes);
//			// System.out.printf("before_colour_line_with_scopes\n");
//			colourer.colourLineWithScopes(allScopes);
//			// System.out.printf("after_colour_line_with_scopes\n");
//		}
//		else {
//			// stdout.printf("no colourer");
//		}
		return (endScope1 != endScope2);
	}

	public Scope getExpectedScope(Scope currentScope, int line, int lineLength, int lineOffset) {
//		System.out.printf("get_expected_scope(%s, %d, %d)\n", currentScope.name, line, lineOffset);
		if (lineOffset == lineLength)
			return null;
		Scope expectedScope = currentScope.firstChildAfter(document.getTextLocation(line, lineOffset));
//		System.out.printf("first_child_after: %s\n", expectedScope.name);
		assert(expectedScope != currentScope);
		if (expectedScope != null) {
			if (expectedScope.getStart().getLine() != line)
				expectedScope = null;
			while (expectedScope != null && expectedScope.isCapture) {
				expectedScope = expectedScope.parent;
			}
		}
		return expectedScope;
	}
	private void removeColourAfter(int lineIx, int something) {
		// TODO: port this function
	}

	public void closeScope(Scanner scanner, Scope expectedScope, int lineIx, String line, 
			int length, Marker m, ArrayList<Scope> allScopes, 
			ArrayList<Scope> closedScopes, ArrayList<Scope> removedScopes) {
		String endMatchString = line.substring(m.from, m.match.getCapture(0).end);
		// stdout.printf("checking for already closed: %s, (%d,%d)-(%d,%d) (%d,%d)-(%d,%d) '%s' '%s'\n",
//     scanner.current_scope.name,
//			  scanner.current_scope.end_line(), scanner.current_scope.end_line_offset(),
//			  line_ix, m.match.end(0), 
//			  scanner.current_scope.inner_end_line(), scanner.current_scope.inner_end_line_offset(),
//				line_ix, m.from,
//				scanner.current_scope.end_match_string, end_match_string);
//		  
		if (//scanner.getCurrentScope().endPos != null &&
				scanner.getCurrentScope().getEnd().equals(document.getTextLocation(lineIx, m.match.getCapture(0).end)) &&
				scanner.getCurrentScope().getInnerEnd().equals(document.getTextLocation(lineIx, m.from)) &&
				scanner.getCurrentScope().endMatchString == endMatchString) {
				// we have already parsed this line and this scope ends here

			// Re-add the captures from the end of the current scope to the 
			// tracking arrays
			for (Scope child : scanner.getCurrentScope().children) {
				if (child.isCapture && 
						child.getStart().getLine() == lineIx) {
					if (!closedScopes.contains(child))
						closedScopes.add(child);
					if (!allScopes.contains(child))
						allScopes.add(child);
				}
			}
			// stdout.printf("closing scope matches expected\n");
		}
		else {
			// stdout.printf("closing scope at %d\n", m.from);
//			if (colourer != null) {
//				colourer.uncolourScope(scanner.getCurrentScope(), false);
//			}
			setInnerEndPosSafely(scanner.getCurrentScope(), m, lineIx, length, 0);
			setEndPosSafely(scanner.getCurrentScope(), m, lineIx, length, 0);
			scanner.getCurrentScope().isOpen = false;
			scanner.getCurrentScope().endMatchString = endMatchString;
			//stdout.printf("end_match_string: '%s'\n", scanner.current_scope.end_match_string);
			handleCaptures(lineIx, length, line, scanner.getCurrentScope(), m, allScopes, closedScopes);
			if (expectedScope != null) {
				scanner.getCurrentScope().removeChild(expectedScope);
				removedScopes.add(expectedScope);
				// @removed_scopes << expected_scope
			}
		}
		removedScopes.add(scanner.getCurrentScope()); // so it gets uncoloured
		closedScopes.add(scanner.getCurrentScope());
		scanner.setCurrentScope(scanner.getCurrentScope().parent);
		allScopes.add(scanner.getCurrentScope());
	}


	public void openScope(Scanner scanner, Scope expectedScope, int lineIx, 
			String line, int length, Marker m,
			ArrayList<Scope> allScopes, ArrayList<Scope> closedScopes, ArrayList<Scope> removedScopes ) {
//		System.out.printf("[opening with %d patterns], \n", ((DoublePattern) m.pattern).patterns.size());
		Scope s = new Scope(mateText, m.pattern.name);
		s.pattern = m.pattern;
		s.openMatch = m.match;
		setStartPosSafely(s, m, lineIx, length, 0);
		setInnerStartPosSafely(s, m, lineIx, length, 0);
		s.beginMatchString = line.substring(m.from, m.match.getCapture(0).end);
//		var end_iter = buffer.end_iter();
//		var end_line = end_iter.get_line();
//		var end_line_index = end_iter.get_line_index();
//		s.inner_end_mark_set(end_line, end_line_index, false);
//		s.end_mark_set(end_line, end_line_index, false);
		s.isOpen = true;
		s.isCapture = false;
		s.parent = scanner.getCurrentScope();
		Scope newScope = s;
		// is this a bug? captures aren't necessarily to be put into all_scopes yet surely?
		if (expectedScope != null) {
			// check mod ending scopes as the new one will not have a closing marker
			// but the expected one will:
			if (s.surfaceIdenticalToModuloEnding(expectedScope)) {
				// stdout.printf("surface_identical_mod_ending: keep expected\n");
				// don't need to do anything as we have already found this,
				// but let's keep the old scope since it will have children and what not.
				newScope = expectedScope;
				for (Scope child : expectedScope.children) {
					closedScopes.add(child);
					allScopes.add(child);
				}
				scanner.setCurrentScope(expectedScope);
			}
			else {
				//stdout.printf("surface_NOT_identical_mod_ending: replace expected\n");
				if (s.overlapsWith(expectedScope)) {
					scanner.getCurrentScope().removeChild(expectedScope);
					// removed_scopes << expected_scope
					removedScopes.add(expectedScope);
				}
				handleCaptures(lineIx, length, line, s, m, allScopes, closedScopes);
				scanner.getCurrentScope().addChild(s);
				scanner.setCurrentScope(s);
			}
		}
		else {
			handleCaptures(lineIx, length, line, s, m, allScopes, closedScopes);
			scanner.getCurrentScope().addChild(s);
			scanner.setCurrentScope(s);
		}
		allScopes.add(newScope);
	}

	private void singleScope(Scanner scanner, Scope expectedScope, int lineIx, 
							String line, int length, Marker m, 
							ArrayList<Scope> allScopes, ArrayList<Scope> closedScopes, 
							ArrayList<Scope> removedScopes) {
		Scope s = new Scope(this.mateText, m.pattern.name);
		s.pattern = m.pattern;
		s.openMatch = m.match;
		setStartPosSafely(s, m, lineIx, length, 0);
		setEndPosSafely(s, m, lineIx, length, 0);
		s.isOpen = false;
		s.isCapture = false;
//		System.out.printf("beginMatchString '%s' %d - %d\n",  new String(line.getBytes(), m.from, m.match.getCapture(0).end - m.from), m.from, m.match.getCapture(0).end);
		s.beginMatchString = new String(line.getBytes(), m.from, m.match.getCapture(0).end - m.from); 
		s.parent = scanner.getCurrentScope();
		Scope newScope = s;
		if (expectedScope != null) {
			if (s.surfaceIdenticalTo(expectedScope)) {
				newScope = expectedScope;
				for (Scope child : expectedScope.children) {
					closedScopes.add(child);
				}
			}
			else {
				handleCaptures(lineIx, length, line, s, m, allScopes, closedScopes);
				if (s.overlapsWith(expectedScope)) {
					if (expectedScope == scanner.getCurrentScope()) {
						// we expected this scope to close, but it doesn't
					}
					else {
						scanner.getCurrentScope().removeChild(expectedScope);
						// removed_scopes << expectedScope
						removedScopes.add(expectedScope);
					}
				}
				scanner.getCurrentScope().addChild(s);
			}
		}
		else {
			handleCaptures(lineIx, length, line, s, m, allScopes, closedScopes);
			scanner.getCurrentScope().addChild(s);
		}
		allScopes.add(newScope);
		closedScopes.add(newScope);
	}

	// TODO: please, give this method a meaningful name.
	private boolean iDontKnowHowToNameThisFunctionButItsDuplicateCode(int lineIx, int length, int to) {
		return to == length && styledText.getLineCount() > lineIx+1;
	}

	public void setStartPosSafely(Scope scope, Marker m, int lineIx, int length, int cap) {
		int to = m.match.getCapture(cap).start;
		if (iDontKnowHowToNameThisFunctionButItsDuplicateCode(lineIx, length, to)) 
			scope.setStartPos(lineIx+1, 0, false);
		else
			scope.setStartPos(lineIx, Math.min(to, length), false);
	}

	public void setInnerStartPosSafely(Scope scope, Marker m, int lineIx, int length, int cap) {
		int to = m.match.getCapture(cap).start;
		if (iDontKnowHowToNameThisFunctionButItsDuplicateCode(lineIx, length, to)) 
			scope.setInnerStartPos(lineIx+1, 0, false);
		else
			scope.setInnerStartPos(lineIx, Math.min(to, length), false);
	}

	public void setInnerEndPosSafely(Scope scope, Marker m, int lineIx, int length, int cap) {
		int from = m.match.getCapture(cap).start;
		if (iDontKnowHowToNameThisFunctionButItsDuplicateCode(lineIx, length, from)) {
			scope.setInnerEndPos(lineIx, length, true);
		}
		else {
			scope.setInnerEndPos(lineIx, Math.min(from, length-1), true);
		}
	}
	
	public void setEndPosSafely(Scope scope, Marker m, int lineIx, int length, int cap) {
		int to = m.match.getCapture(cap).end;
		if (iDontKnowHowToNameThisFunctionButItsDuplicateCode(lineIx, length, to)) {
			scope.setEndPos(lineIx, length, true);
		}
		else {
			scope.setEndPos(lineIx, Math.min(to, length-1), true);
		}
	}
	
	// Opens scopes for captures AND creates closing regexp from
	// captures if necessary.
	public void handleCaptures(int lineIx, int length, String line, 
			Scope scope, Marker m, ArrayList<Scope> allScopes, ArrayList<Scope> closedScopes) {
		makeClosingRegex(line, scope, m);
		collectChildCaptures(lineIx, length, scope, m, allScopes, closedScopes);
	}

	public Rx makeClosingRegex(String line, Scope scope, Marker m) {
		// System.out.printf("make_closing_regex\n");
		// new_end = pattern.end.gsub(/\\([0-9]+)/) do
		// 	md.captures.send(:[], $1.to_i-1)
		// end
		if (m.pattern instanceof DoublePattern && !m.isCloseScope) {
			DoublePattern dp = (DoublePattern) m.pattern;
			//stdout.printf("making closing regex: %s (%d)\n", dp.end_string, (int) dp.end_string.length);
			Rx rx = Rx.createRx("\\\\(\\d+)");
			Match match;
			int pos = 0;
			StringBuilder src = new StringBuilder("");
			boolean found = false;
			while ((match = rx.search(dp.endString, pos, (int) dp.endString.length())) != null) {
				found = true;
				src.append(dp.endString.substring(pos, match.getCapture(0).start));
				String numstr = dp.endString.substring(match.getCapture(1).start, match.getCapture(1).end);
				int num = Integer.parseInt(numstr);
				// System.out.printf("capture found: %d\n", num);
				String capstr = line.substring(m.match.getCapture(num).start, m.match.getCapture(num).end);
				src.append(capstr);
				pos = match.getCapture(1).end;
			}
			if (found)
				src.append(dp.endString.substring(pos, dp.endString.length()));
			else
				src.append(dp.endString);
//			stdout.printf("src: '%s'\n", src.str);
			scope.closingRegex = Rx.createRx(src.toString());
		}
		return null;
	}

	public void collectChildCaptures(int lineIx, int length, Scope scope, 
			Marker m, ArrayList<Scope> allScopes, ArrayList<Scope> closedScopes) {
		Scope s;
		Map<Integer, String> captures;
		if (m.pattern instanceof SinglePattern) {
			captures = ((SinglePattern) m.pattern).captures;
		}
		else {
			if (m.isCloseScope) {
				captures = ((DoublePattern) m.pattern).endCaptures;
			}
			else {
				captures = ((DoublePattern) m.pattern).beginCaptures;
			}
			if (((DoublePattern) m.pattern).bothCaptures != null) {
				captures = ((DoublePattern) m.pattern).bothCaptures;
			}
		}
		List<Scope> captureScopes = new ArrayList<Scope>();
		// create capture scopes
		if (captures != null) {
			for (Integer cap : captures.keySet()) {
				if (m.match.numCaptures() - 1 >= cap && m.match.getCapture(cap).start != -1) {
					s = new Scope(mateText, captures.get(cap));
					s.pattern = scope.pattern;
					s.setStartPos(lineIx, Math.min(m.match.getCapture(cap).start, length-1), false);
					setInnerEndPosSafely(s, m, lineIx, length, cap);
					setEndPosSafely(s, m, lineIx, length, cap);
					s.isOpen = false;
					s.isCapture = true;
					s.parent = scope;
					captureScopes.add(s);
					allScopes.add(s);
					closedScopes.add(s);
				}
			}
		}
		// Now we arrange the capture scopes into a tree under the matched
		// scope. We do this by processing the captures in order of offset and 
		// length. For each capture, we check to see if it is a child of an already 
		// placed capture, and if so we add it as a child (we know that the latest 
		// such capture is the one to add it to by the ordering). If not we
		// add it as a child of the matched scope.

		List<Scope> placedScopes = new ArrayList<Scope>();
		Scope parentScope;
		while (captureScopes.size() > 0) {
			s = null;
			// find first and longest remaining scope (put it in 's')
			for (Scope cs : captureScopes) {
				if (s == null || 
						cs.getStart().compareTo(s.getStart()) < 0 || 
						(cs.getStart().compareTo(s.getStart()) == 0 && cs.getLength() > s.getLength())) {
					s = cs;
				}
			}
			//System.out.printf("arrange: %s, start: %d, length: %d\n", s.name, s.startOffset(), s.endOffset() - s.startOffset());
			// look for somewhere to put it from placed_scopes
			parentScope = null;
			for (Scope ps : placedScopes) {
				if (s.getStart().compareTo(ps.getStart()) >= 0 && s.getEnd().compareTo(ps.getEnd()) <= 0) {
					parentScope = ps;
				}
			}
			if (parentScope != null) {
				parentScope.addChild(s);
				s.parent = parentScope;
			}
			else {
				scope.addChild(s);
			}
			placedScopes.add(s);
			captureScopes.remove(s);
		}
	}

	private void clearLine(int lineIx, Scope startScope, ArrayList<Scope> allScopes, 
							ArrayList<Scope> closedScopes, ArrayList<Scope> removedScopes) {
		// If we are reparsing, we might find that some scopes have disappeared,
		// delete them:
		Scope cs = startScope;
		while (cs != null) {
			// stdout.printf("  removing_scopes from: %s\n", cs.name);
			ArrayList<Scope> newRemovedScopes = cs.deleteAnyOnLineNotIn(lineIx, allScopes);
			removedScopes.addAll(newRemovedScopes);
			cs = cs.parent;
		}

		// any that we expected to close on this line that now don't?
		// first build list of scopes that close on this line (including ones
		// that did but haven't been removed yet).
		ArrayList<Scope> scopesThatClosedOnLine = new ArrayList<Scope>();
		Scope ts = startScope;
		while (ts.parent != null) {
			if (ts.getInnerEnd().getLine() == lineIx) {
				scopesThatClosedOnLine.add(ts);
			}
			ts = ts.parent;
		}
		for (Scope s : scopesThatClosedOnLine) {
			if (!closedScopes.contains(s)) {
				if (s.isCapture) {
					s.parent.removeChild(s);
					removedScopes.add(s);
					// @removed_scopes << s
				}
				else {
//					if (colourer != null)
//						colourer.uncolourScope(s, false);
					// s.inner_end_mark = null;
					// s.end_mark = null;
					// s.is_open = true;
					int line = styledText.getLineCount() - 1;
					int lineOffset = styledText.getCharCount() - styledText.getOffsetAtLine(line);
					// TODO: why are we setting an end position here for the end of the doc,
					// but using a null to represent open elsewhere?
					s.setInnerEndPos(line, lineOffset, false);
					s.setEndPos(line, lineOffset, false);
					s.isOpen = true;
				}
			}
		}
	}
}