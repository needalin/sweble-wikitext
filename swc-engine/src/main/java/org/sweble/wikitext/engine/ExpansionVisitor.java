/**
 * Copyright 2011 The Open Source Research Group,
 *                University of Erlangen-Nürnberg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sweble.wikitext.engine;

import static org.sweble.wikitext.parser.utils.AstBuilder.astList;
import static org.sweble.wikitext.parser.utils.AstBuilder.astText;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.sweble.wikitext.engine.config.Namespace;
import org.sweble.wikitext.engine.config.WikiConfig;
import org.sweble.wikitext.engine.log.ParseException;
import org.sweble.wikitext.engine.log.ResolveMagicWordLog;
import org.sweble.wikitext.engine.log.ResolveParameterLog;
import org.sweble.wikitext.engine.log.ResolveParserFunctionLog;
import org.sweble.wikitext.engine.log.ResolveRedirectLog;
import org.sweble.wikitext.engine.log.ResolveTagExtensionLog;
import org.sweble.wikitext.engine.log.ResolveTransclusionLog;
import org.sweble.wikitext.engine.log.UnhandledException;
import org.sweble.wikitext.engine.utils.EngineTextUtils;
import org.sweble.wikitext.parser.AstNodeTypes;
import org.sweble.wikitext.parser.LinkTargetException;
import org.sweble.wikitext.parser.WarningSeverity;
import org.sweble.wikitext.parser.nodes.Newline;
import org.sweble.wikitext.parser.nodes.PageSwitch;
import org.sweble.wikitext.parser.nodes.Redirect;
import org.sweble.wikitext.parser.nodes.TagExtension;
import org.sweble.wikitext.parser.nodes.Template;
import org.sweble.wikitext.parser.nodes.TemplateArgument;
import org.sweble.wikitext.parser.nodes.TemplateParameter;
import org.sweble.wikitext.parser.nodes.WtNode;
import org.sweble.wikitext.parser.nodes.WtContentNode;
import org.sweble.wikitext.parser.nodes.WtNodeList;
import org.sweble.wikitext.parser.nodes.WtText;
import org.sweble.wikitext.parser.nodes.XmlAttribute;
import org.sweble.wikitext.parser.utils.StringConversionException;
import org.sweble.wikitext.parser.utils.StringConverter;
import org.sweble.wikitext.parser.utils.StringConverterPartial;

import de.fau.cs.osr.utils.StopWatch;
import de.fau.cs.osr.utils.Tuple2;

public final class ExpansionVisitor
		extends
			PtkAstVisitor
{
	private static final Pattern STARTS_WITH_BLOCK_ELEMENT =
			Pattern.compile("^(\\{\\||:|;|#|\\*)");
	
	// =========================================================================
	
	private static final String SKIP_ATTR_NAME = "__SKIP__";
	
	private final ExpansionFrame expFrame;
	
	private final WtContentNode frameLog;
	
	private final ExpansionDebugHooks hooks;
	
	private final boolean timingEnabled;
	
	private final boolean catchAll;
	
	private boolean hadNewlineGlobal;
	
	// =========================================================================
	
	public ExpansionVisitor(
			ExpansionFrame expFrame,
			WtContentNode frameLog,
			ExpansionDebugHooks hooks,
			boolean timingEnabled,
			boolean catchAll)
	{
		this.expFrame = expFrame;
		this.frameLog = frameLog;
		this.hooks = hooks;
		this.timingEnabled = timingEnabled;
		this.catchAll = catchAll;
	}
	
	// =========================================================================
	
	/**
	 * Visit a node which if of no intereset to the expansion process.
	 * 
	 * Visits all it's children to see if there is something that has to be
	 * expanded. If one of the children got expanded the child will be replaced
	 * by the expanded AST.
	 */
	@Override
	protected WtNode visitUnspecific(WtNode n)
	{
		mapInPlace(n);
		return n;
	}
	
	@Override
	protected Object resolveAndVisit(WtNode n, int type) throws ExpansionException
	{
		switch (type)
		{
		// -- These set the hadNewline flag --
		
			case WtNode.NT_TEXT:
				return visitText((WtText) n);
			case AstNodeTypes.NT_NEWLINE:
				return visitNewline((Newline) n);
				
				// -- We must NOT reset hadNewline for these elements! --
				
			case AstNodeTypes.NT_ERROR:
				return visitError(n);
				
			case AstNodeTypes.NT_IGNORED:
			case AstNodeTypes.NT_XML_COMMENT:
			case WtNode.NT_NODE_LIST:
				return visitUnspecific(n);
				
				// -- We MUST reset hadNewline for these elements! --
				
			default:
				hadNewlineGlobal = false;
				switch (type)
				{
					case AstNodeTypes.NT_REDIRECT:
						return visit((Redirect) n);
					case AstNodeTypes.NT_TEMPLATE_PARAMETER:
						return visit((TemplateParameter) n);
					case AstNodeTypes.NT_TEMPLATE:
						return visit((Template) n);
					case AstNodeTypes.NT_TAG_EXTENSION:
						return visit((TagExtension) n);
					case AstNodeTypes.NT_PAGE_SWITCH:
						return visit((PageSwitch) n);
					default:
						return visitUnspecific(n);
						
						// We don't care about other node types than the ones handled above.
						//return super.resolveAndVisit(node, type);
				}
		}
	}
	
	// =========================================================================
	// ==
	// ==  Newlines
	// ==
	// =========================================================================
	
	private WtNode visitNewline(Newline n)
	{
		hadNewlineGlobal = true;
		return n;
	}
	
	private WtNode visitText(WtText n)
	{
		String text = n.getContent();
		if (!text.isEmpty())
		{
			hadNewlineGlobal = false;
			if (text.indexOf('\n') != -1)
				hadNewlineGlobal = true;
		}
		return n;
	}
	
	// =========================================================================
	// ==
	// ==  Error
	// ==
	// =========================================================================
	
	private Object visitError(WtNode n)
	{
		/* Don't descend into error nodes!
		 */
		return n;
	}
	
	// =========================================================================
	// ==
	// ==  R e d i r e c t
	// ==
	// =========================================================================
	
	private WtNode visit(Redirect n) throws ExpansionException
	{
		if (skip(n))
			return n;
		
		if (expFrame.isNoRedirect())
			return n;
		
		String target = n.getTarget();
		
		WtNode result = resolveRedirectWrapper(n, target);
		if (result == null)
			result = markError(n);
		
		return result;
	}
	
	/**
	 * @return Returns null if the redirect target page cannot be found. Returns
	 *         the redirect node n itself, if an error occurred in the expansion
	 *         process. Otherwise the expanded form of the redirect target page
	 *         will be returned.
	 */
	private WtNode resolveRedirectWrapper(Redirect n, String target) throws ExpansionException
	{
		if (hooks != null)
		{
			WtNode cont = hooks.beforeResolveRedirect(this, n, target);
			if (cont != ExpansionDebugHooks.PROCEED)
				return cont;
		}
		
		ResolveRedirectLog log = null;
		if (frameLog != null)
		{
			log = new ResolveRedirectLog(target, false);
			frameLog.getContent().add(log);
		}
		
		StopWatch stopWatch = null;
		if (timingEnabled)
		{
			stopWatch = new StopWatch();
			stopWatch.start();
		}
		
		WtNode result = null;
		try
		{
			result = expandRedirectionTargetPage(n, target, log);
		}
		catch (Exception e)
		{
			result = markError(n, e);
			
			if (log != null)
				logUnhandledException(log, e);
			
			if (!catchAll)
				throw new ExpansionException(e);
		}
		finally
		{
			if (timingEnabled && log != null)
				log.setTimeNeeded(stopWatch.getElapsedTime());
		}
		
		return (hooks != null) ?
				hooks.afterResolveRedirect(this, n, target, result, log) :
				result;
	}
	
	/**
	 * Expands the target page of the redirect statement inside a NEW expansion
	 * frame and returns the expanded AST.
	 * 
	 * @return Returns null if the redirect target page cannot be found. Returns
	 *         the redirect node n itself, if an error occurred in the expansion
	 *         process. Otherwise the expanded form of the redirect target page
	 *         will be returned.
	 */
	private WtNode expandRedirectionTargetPage(
			Redirect n,
			String target,
			ResolveRedirectLog log) throws Exception
	{
		PageTitle title;
		try
		{
			title = PageTitle.make(getWikiConfig(), target);
		}
		catch (LinkTargetException e)
		{
			if (log != null)
				log.getContent().add(new ParseException(e.getMessage()));
			
			fileInvalidPageNameWarning(n, target);
			
			return n;
		}
		
		if (log != null)
			log.setCanonical(title.getDenormalizedFullTitle());
		
		FullPage page = getWikitext(title);
		if (page != null)
		{
			/* Since we are redirecting some stuff is passed on from this frame
			 * to the frame in which the target page is expanded: - The
			 * forInclusion flag is passed on.
			 * 
			 * - If the page we are redirecting from was thought for inclusion
			 *   so will be the replacement page.
			 * 
			 * - The arguments that were passed to the page we are redirecting
			 *   from will also be passed to the replacement page.
			 */
			CompiledPage compiledPage = getCompiler().preprocessAndExpand(
					expFrame.getCallback(),
					page.getId(),
					page.getText(),
					expFrame.isForInclusion(),
					expFrame.getEntityMap(),
					expFrame.getArguments(),
					expFrame.getRootFrame(),
					expFrame);
			
			log.setSuccess(true);
			
			return mergeLogsAndWarnings(log, compiledPage);
		}
		else
		{
			filePageNotFoundWarning(n, title);
			
			return null;
		}
	}
	
	// =========================================================================
	// ==
	// ==  T e m p l a t e
	// ==
	// =========================================================================
	
	/**
	 * A template statement was found.
	 * 
	 * First fully expand the name of the template. Then determine if this is a
	 * call to a parser function, a transclusion or magic word.
	 * 
	 * This method WILL ONLY EXPAND the parameters of the template statement if
	 * it is a transclusion. If it is a call to a parser function or magic word,
	 * the respective parser function can decide if it needs to expand
	 * parameters.
	 */
	private WtNode visit(Template n) throws ExpansionException
	{
		if (skip(n))
			return n;
		
		// Safe newline state!
		boolean hadNewline = this.hadNewlineGlobal;
		
		// First: Fully expand name.
		WtNodeList name = (WtNodeList) dispatch(n.getName());
		//n.setName(name);
		
		Tuple2<String, WtNodeList> conv = StringConverterPartial.convert(name);
		String title = conv._1;
		WtNodeList tail = conv._2;
		
		// DO NOT expand parameters (yet)
		ArrayList<TemplateArgument> args =
				new ArrayList<TemplateArgument>(n.getArgs().size() + 1);
		
		for (Object arg : n.getArgs())
			args.add((TemplateArgument) arg);
		
		// First see if it is a parser function
		WtNode result = resolveTemplateAsPfn(n, title, tail, args, hadNewline);
		
		if (result == null)
		{
			// A template or magic word cannot be resolved if the name is not 
			// fully convertable to a string
			if (tail == null)
			{
				// If not see if it is a magic word
				// Magic Word calls cannot have arguments!
				if (args.isEmpty())
					result = resolveTemplateAsMagicWord(n, title, hadNewline);
				
				// If not try to transclude
				if (result == null)
					result = resolveTemplateAsTransclusion(n, title, args, hadNewline);
			}
			else
			{
				StringConversionException e = new StringConversionException(tail);
				
				if (frameLog != null)
					frameLog.getContent().add(new ParseException(e.getMessage()));
				
				fileInvalidTemplateNameWarning(n, e);
			}
		}
		
		if (result == null)
			result = markError(n);
		else if (result != n)
			this.hadNewlineGlobal = endedWithNewline(result);
		
		return result;
	}
	
	private boolean endedWithNewline(WtNode result)
	{
		// FIXME: IMPLEMENT!
		return false;
	}
	
	/**
	 * Try to resolve the template as parser function.
	 * 
	 * A parser function looks like a transclusion, only that its name is
	 * separated from the first argument by a colon. This method checks if the
	 * template name contains a colon and then tries to look up the parser
	 * function.
	 * 
	 * @return Returns the expanded AST of produced by the parser function if
	 *         everything went fine. If the given template does not have parser
	 *         function syntax (no colon) or no matching parser function can be
	 *         found null is returned. If a parser function was found but an
	 *         error occurs afterwards, the template node itself is returned.
	 */
	private WtNode resolveTemplateAsPfn(
			Template n,
			String title,
			WtNodeList tail,
			ArrayList<TemplateArgument> args,
			boolean hadNewline) throws ExpansionException
	{
		int i = title.indexOf(':');
		if (i == -1)
			return null;
		
		/*
		title = title.trim();
		
		boolean hash = !title.isEmpty() && title.charAt(0) == '#';
		String name = hash ?
				(title.substring(1, i)) :
				(title.substring(0, i) + ":");
		*/
		
		String name = title.substring(0, i).trim() + ":";
		
		ParserFunctionBase pfn = getWikiConfig().getParserFunction(name);
		if (pfn == null)
			return null;
		
		String arg0Prefix = title.substring(i + 1).trim();
		
		List<? extends WtNode> argsValues = preparePfnArguments(
				pfn.getArgMode(),
				arg0Prefix,
				tail,
				args);
		
		return invokePfn(n, pfn, argsValues, hadNewline);
	}
	
	/**
	 * Prepare arguments to pass to parser function.
	 * 
	 * While a parser function looks like a template it does not support
	 * parameter names. Therefore, all `name "=" value' parameters of the
	 * template will be converted into a single value `$name=$value'.
	 * 
	 * Also the first argument begins after the colon, the first template
	 * argument is actually the second argument to the parser function. To fix
	 * this, the part after the colon is stitched together to form the first
	 * argument to the parser function.
	 * 
	 * The arguments of a parser function ARE NOT EXPANDED! This has to be done
	 * by each parser function individually, IF the parser function requires it.
	 * 
	 * @param pfnArgumentMode
	 */
	private List<? extends WtNode> preparePfnArguments(
			PfnArgumentMode pfnArgumentMode,
			String arg0Prefix,
			WtNodeList tail,
			List<TemplateArgument> args)
	{
		/*

		MediaWiki has two ways of invoking parser functions:
		1) The arguments are passed in preprocessor DOM format.
		2) The arguments are expanded and passed as text.
		
		In case 2) the expanded arguments are TRIMMED! before they are passed to 
		the parser function. It is my understanding that, even though the 
		arguments have been expanded and converted to text, there are still 
		so-called markers in that text.
		
		Places in the code:
		includes/parser/Parser.php:3172-3186 (83b70491fd7cbe9f8a0eadec0f9500636742f6fd)
		
		 */
		
		switch (pfnArgumentMode)
		{
			case EXPANDED_AND_TRIMMED_VALUES:
			{
				ArrayList<WtNode> argValues = new ArrayList<WtNode>(args.size() + 1);
				
				WtNode arg0 = astList(new WtText(arg0Prefix), tail);
				
				arg0 = EngineTextUtils.trim((WtNode) dispatch(arg0));
				argValues.add(arg0);
				
				for (int j = 0; j < args.size(); ++j)
				{
					TemplateArgument tmplArg = args.get(j);
					
					WtNode arg = tmplArg.getHasName() ?
							astList(tmplArg.getName(), astText("="), tmplArg.getValue()) :
							tmplArg.getValue();
					
					arg = EngineTextUtils.trim((WtNode) dispatch(arg));
					argValues.add(arg);
				}
				
				return argValues;
			}
			case TEMPLATE_ARGUMENTS:
			{
				ArrayList<TemplateArgument> argsWithArg0 =
						new ArrayList<TemplateArgument>(args.size() + 1);
				
				argsWithArg0.add(
						new TemplateArgument(
								new WtNodeList(new WtText(arg0Prefix), tail),
								false));
				
				for (TemplateArgument arg : args)
					argsWithArg0.add(arg);
				
				return argsWithArg0;
			}
			case UNEXPANDED_VALUES:
			{
				ArrayList<WtNode> argValues = new ArrayList<WtNode>(args.size() + 1);
				
				argValues.add(new WtNodeList(new WtText(arg0Prefix), tail));
				
				for (int j = 0; j < args.size(); ++j)
				{
					TemplateArgument arg = args.get(j);
					argValues.add(arg.getHasName() ?
							astList(arg.getName(), astText("="), arg.getValue()) :
							arg.getValue());
				}
				
				return argValues;
			}
			default:
				throw new InternalError();
		}
	}
	
	/**
	 * Try to resolve the template as magic word (and call the associated parser
	 * function).
	 * 
	 * A template that is not a call to a parser function (no colon in name) and
	 * does not take any parameters can be a call to a parser function
	 * identified solely by a magic word.
	 * 
	 * @return Returns the expanded AST of produced by the parser function if
	 *         everything went fine. If the given template does not have parser
	 *         function syntax (no colon) or no matching parser function can be
	 *         found null is returned. If a parser function was found but an
	 *         error occurs afterwards, the template node itself is returned.
	 */
	private WtNode resolveTemplateAsMagicWord(
			Template n,
			String title,
			boolean hadNewline) throws ExpansionException
	{
		ParserFunctionBase pfn = getWikiConfig().getParserFunction(title);
		if (pfn == null)
			return null;
		
		List<WtNode> argsValues = Collections.emptyList();
		
		return invokePfn(n, pfn, argsValues, hadNewline);
	}
	
	/**
	 * Nothing left to do but call the actual parser function.
	 */
	private WtNode invokePfn(
			Template n,
			ParserFunctionBase pfn,
			List<? extends WtNode> argsValues,
			boolean hadNewline) throws ExpansionException
	{
		if (hooks != null)
		{
			WtNode cont = hooks.beforeResolveParserFunction(this, n, pfn, argsValues);
			if (cont != ExpansionDebugHooks.PROCEED)
				return cont;
		}
		
		ResolveParserFunctionLog log = null;
		if (frameLog != null)
		{
			log = new ResolveParserFunctionLog(pfn.getId(), false);
			frameLog.getContent().add(log);
		}
		
		StopWatch stopWatch = null;
		if (timingEnabled)
		{
			stopWatch = new StopWatch();
			stopWatch.start();
		}
		
		WtNode result = null;
		try
		{
			result = pfn.invoke(n, expFrame, argsValues);
			if (result == null)
				throw new NullPointerException("Parser function `" + pfn.getId() + "' returned null value!");
			
			if (result != n)
				result = treatBlockElements(n, result);
			
			log.setSuccess(true);
		}
		catch (Exception e)
		{
			result = markError(n, e);
			
			if (log != null)
				logUnhandledException(log, e);
			
			if (!catchAll)
				throw new ExpansionException(e);
		}
		finally
		{
			if (timingEnabled && log != null)
				log.setTimeNeeded(stopWatch.getElapsedTime());
		}
		
		return (hooks != null) ?
				hooks.afterResolveParserFunction(this, n, pfn, argsValues, result, log) :
				result;
	}
	
	/**
	 * Try to resolve the template as transclusion.
	 * 
	 * @return Returns the expanded AST of the transcluded page if everything
	 *         went fine. If the page to transclude cannot be found, null is
	 *         returned. If the page was found but an error occurs afterwards,
	 *         the template node itself is returned.
	 */
	private WtNode resolveTemplateAsTransclusion(
			Template n,
			String title,
			ArrayList<TemplateArgument> args,
			boolean hadNewline) throws ExpansionException
	{
		if (hooks != null)
		{
			WtNode cont = hooks.beforeResolveTransclusion(this, n, title, args);
			if (cont != ExpansionDebugHooks.PROCEED)
				return cont;
		}
		
		ResolveTransclusionLog log = null;
		if (frameLog != null)
		{
			log = new ResolveTransclusionLog(title, false);
			frameLog.getContent().add(log);
		}
		
		StopWatch stopWatch = null;
		if (timingEnabled)
		{
			stopWatch = new StopWatch();
			stopWatch.start();
		}
		
		WtNode result = null;
		try
		{
			result = transcludePage(n, title, args, log);
		}
		catch (Exception e)
		{
			result = markError(n, e);
			
			if (log != null)
				logUnhandledException(log, e);
			
			if (!catchAll)
				throw new ExpansionException(e);
		}
		finally
		{
			if (timingEnabled && log != null)
				log.setTimeNeeded(stopWatch.getElapsedTime());
		}
		
		return (hooks != null) ?
				hooks.afterResolveTransclusion(this, n, title, args, result, log) :
				result;
	}
	
	/**
	 * Expands the name of the template, the template parameters and then
	 * preprocesses and expands the page to transclude.
	 * 
	 * @return Returns the expanded AST of the transcluded page if everything
	 *         went fine. If the page to transclude cannot be found, null is
	 *         returned. If the page was found but an error occurs afterwards,
	 *         the template node itself is returned.
	 */
	private WtNode transcludePage(
			Template n,
			String target,
			List<TemplateArgument> args,
			ResolveTransclusionLog log) throws Exception
	{
		Namespace tmplNs = getWikiConfig().getTemplateNamespace();
		
		PageTitle title;
		try
		{
			title = PageTitle.make(getWikiConfig(), target, tmplNs);
		}
		catch (LinkTargetException e)
		{
			if (log != null)
				log.getContent().add(new ParseException(e.getMessage()));
			
			fileInvalidPageNameWarning(n, target);
			
			return n;
		}
		
		checkTransclusionRecursion(title);
		
		log.setCanonical(title.getDenormalizedFullTitle());
		
		FullPage page = getWikitext(title);
		if (page != null)
		{
			// EXPANDS ARGUMENTS!
			Map<String, WtNode> tmplArgs = prepareTransclusionArguments(args, log);
			
			CompiledPage compiledPage = getCompiler().preprocessAndExpand(
					expFrame.getCallback(),
					page.getId(),
					page.getText(),
					true,
					expFrame.getEntityMap(),
					tmplArgs,
					expFrame.getRootFrame(),
					expFrame);
			
			log.setSuccess(true);
			
			WtNode tResult = mergeLogsAndWarnings(log, compiledPage);
			
			return treatBlockElements(n, tResult);
		}
		else
		{
			filePageNotFoundWarning(n, title);
			
			return null;
		}
	}
	
	/**
	 * Check if a page transcludes itself more than once (directly or
	 * indirectly).
	 */
	private void checkTransclusionRecursion(PageTitle title) throws RecursiveTransclusionException
	{
		int count = 0;
		
		ExpansionFrame f = expFrame;
		while (f != null)
		{
			if (f.getTitle().equals(title))
			{
				if (++count > 2)
					throw new RecursiveTransclusionException(title, count);
			}
			
			f = f.getParentFrame();
		}
	}
	
	/**
	 * Prepares the template argument list for transclusion. This encompasses
	 * the expansion of name and value of each argument.
	 * 
	 * Each argument is added to the mapping with its one-based index as key.
	 * 
	 * If an argument has a name which can be resolved to a string, the argument
	 * will additionally be put into the mapping with the resolved name as key.
	 */
	private Map<String, WtNode> prepareTransclusionArguments(
			List<TemplateArgument> args,
			ResolveTransclusionLog log)
	{
		HashMap<String, WtNode> transclArgs = new HashMap<String, WtNode>();
		
		int index = 1;
		for (TemplateArgument arg : args)
		{
			// EXPAND VALUE!
			WtNodeList value = (WtNodeList) dispatch(arg.getValue());
			
			boolean named = false;
			if (arg.getHasName())
			{
				// ONLY TRIM NAMED VALUES!
				value = (WtNodeList) EngineTextUtils.trim(value);
				
				// EXPAND NAME!
				WtNodeList name = (WtNodeList) dispatch(arg.getName());
				
				try
				{
					String nameStr = StringConverter.convert(name).trim();
					
					if (!nameStr.isEmpty())
					{
						transclArgs.put(nameStr, value);
						named = true;
					}
				}
				catch (StringConversionException e)
				{
					if (log != null)
						log.getContent().add(new ParseException(e.getMessage()));
					
					fileInvalidArgumentNameWarning(arg, e);
				}
			}
			
			if (!named)
			{
				String id = String.valueOf(index);
				
				WtNode prev = transclArgs.put(id, value);
				// Automatic indices never overwrite!
				if (prev != null)
					transclArgs.put(id, prev);
				
				// Only unnamed arguments increase the index
				index++;
			}
		}
		
		return transclArgs;
	}
	
	// =========================================================================
	// ==
	// ==  T e m p l a t e  P a r a m e t e r
	// ==
	// =========================================================================
	
	/**
	 * A template parameter was found.
	 * 
	 * First fully expand the name of the parameter. Then try to find the value
	 * in the argument list that was passed to this frame. Otherwise try to use
	 * the default value.
	 * 
	 * This method WILL ONLY EXPAND the default value if no value could be found
	 * among the frame arguments.
	 */
	private WtNode visit(TemplateParameter n) throws ExpansionException
	{
		if (skip(n))
			return n;
		
		// Fully expand name!
		WtNodeList name = (WtNodeList) dispatch(n.getName());
		
		String nameStr = null;
		try
		{
			nameStr = StringConverter.convert(name);
		}
		catch (StringConversionException e)
		{
			if (frameLog != null)
				frameLog.getContent().add(new ParseException(e.getMessage()));
			
			fileInvalidParameterNameWarning(n, e);
		}
		
		WtNode value = null;
		if (nameStr != null)
			value = resolveParameterWrapper(n, nameStr.trim(), n.getDefaultValue());
		
		if (value == null)
			value = markError(n);
		
		return value;
	}
	
	/**
	 * Try to map the parameter to its value.
	 * 
	 * @return Returns the expanded value to which the parameter resolved. If no
	 *         value can be found in the frame arguments and no default value is
	 *         given, null is returned. Otherwise, if an error occurs, the
	 *         parameter node itself is returned.
	 */
	private WtNode resolveParameterWrapper(
			TemplateParameter n,
			String name,
			TemplateArgument defaultValue) throws ExpansionException
	{
		if (hooks != null)
		{
			WtNode cont = hooks.beforeResolveParameter(this, n, name);
			if (cont != ExpansionDebugHooks.PROCEED)
				return cont;
		}
		
		ResolveParameterLog log = null;
		if (frameLog != null)
		{
			log = new ResolveParameterLog(name, false);
			frameLog.getContent().add(log);
		}
		
		StopWatch stopWatch = null;
		if (timingEnabled)
		{
			stopWatch = new StopWatch();
			stopWatch.start();
		}
		
		WtNode result = null;
		try
		{
			result = resolveParameter(n, name, defaultValue, log);
		}
		catch (Exception e)
		{
			result = markError(n, e);
			
			if (log != null)
				logUnhandledException(log, e);
			
			if (!catchAll)
				throw new ExpansionException(e);
		}
		finally
		{
			if (timingEnabled && log != null)
				log.setTimeNeeded(stopWatch.getElapsedTime());
		}
		
		return (hooks != null) ?
				hooks.afterResolveParameter(this, n, name, result, log) :
				result;
	}
	
	/**
	 * Tries to get the parameter value from the list of arguments passed to
	 * this frame. If no matching argument can be found, the default value is
	 * used, if present. Otherwise, null is returned.
	 */
	private WtNode resolveParameter(
			TemplateParameter n,
			String name,
			TemplateArgument defaultValue,
			ResolveParameterLog log)
	{
		WtNode value = getFrameArgument(name);
		
		if (value == null && defaultValue != null)
		{
			// Only the first value after the pipe is the default 
			// value. The rest is ignored.
			
			value = defaultValue.getValue();
			if (defaultValue.getHasName())
			{
				// The default value cannot be separated into name and 
				// value
				
				value = astList(
						defaultValue.getName(),
						astText("="),
						defaultValue.getValue());
			}
			
			// EXPAND DEFAULT VALUE!
			value = (WtNode) dispatch(value);
		}
		
		if (value != null)
		{
			log.setSuccess(true);
			
			//value = treatBlockElements(n, value);
		}
		
		return value;
	}
	
	// =========================================================================
	// ==
	// ==  T A G   E X T E N S I O N
	// ==
	// =========================================================================
	
	private WtNode visit(TagExtension n) throws ExpansionException
	{
		if (skip(n))
			return n;
		
		WtNode result = resolveTagExtensionWrapper(n, n.getName(), n.getXmlAttributes(), n.getBody());
		if (result == null)
			result = markError(n);
		
		return result;
	}
	
	private WtNode resolveTagExtensionWrapper(
			TagExtension n,
			String name,
			WtNodeList attrs,
			String body) throws ExpansionException
	{
		if (hooks != null)
		{
			WtNode cont = hooks.beforeResolveTagExtension(this, n, name, attrs, body);
			if (cont != ExpansionDebugHooks.PROCEED)
				return cont;
		}
		
		ResolveTagExtensionLog log = null;
		if (frameLog != null)
		{
			log = new ResolveTagExtensionLog(name, false);
			frameLog.getContent().add(log);
		}
		
		StopWatch stopWatch = null;
		if (timingEnabled)
		{
			stopWatch = new StopWatch();
			stopWatch.start();
		}
		
		WtNode result = null;
		try
		{
			result = resolveTagExtension(n, name, attrs, body, log);
		}
		catch (Exception e)
		{
			result = markError(n, e);
			
			if (log != null)
				logUnhandledException(log, e);
			
			if (!catchAll)
				throw new ExpansionException(e);
		}
		finally
		{
			if (timingEnabled && log != null)
				log.setTimeNeeded(stopWatch.getElapsedTime());
		}
		
		return (hooks != null) ?
				hooks.afterResolveTagExtension(this, n, name, attrs, body, result, log) :
				result;
	}
	
	private WtNode resolveTagExtension(
			TagExtension n,
			String name,
			WtNodeList attrs,
			String body,
			ResolveTagExtensionLog log)
	{
		TagExtensionBase te = getWikiConfig().getTagExtension(name);
		if (te == null)
			/* This should not happen: If you register a tag extension with the
			 * parser (which only then will produce this tag extension node)
			 * there also has to be a tag extension object.
			 * 
			 * That's not true any more: the #tag: pfn can also generate
			 * tag extensions, whether they were registered or not.
			 */
			//throw new InternalError("Cannot find tag extension: " + name);
			return null;
		
		HashMap<String, WtNodeList> attrMap = prepareTagExtensionAttributes(attrs);
		
		WtNode result = te.invoke(expFrame, n, attrMap, body);
		
		log.setSuccess(true);
		
		return result;
	}
	
	/**
	 * Converts the list of attributes into a map, ignoring any non-attribute
	 * nodes.
	 */
	private HashMap<String, WtNodeList> prepareTagExtensionAttributes(
			WtNodeList attrs)
	{
		HashMap<String, WtNodeList> attrMap = new HashMap<String, WtNodeList>();
		
		for (WtNode attr : attrs)
		{
			if (attr.isNodeType(AstNodeTypes.NT_XML_ATTRIBUTE))
			{
				XmlAttribute a = (XmlAttribute) attr;
				attrMap.put(a.getName(), a.getValue());
			}
		}
		
		return attrMap;
	}
	
	// =========================================================================
	// ==
	// ==  M A G I C   W O R D
	// ==
	// =========================================================================
	
	private WtNode visit(PageSwitch n) throws ExpansionException
	{
		if (skip(n))
			return n;
		
		WtNode result = resolveMagicWordWrapper(n, n.getName());
		if (result == null)
			result = markError(n);
		
		return result;
	}
	
	private WtNode resolveMagicWordWrapper(
			PageSwitch n,
			String name) throws ExpansionException
	{
		if (hooks != null)
		{
			WtNode cont = hooks.beforeResolvePageSwitch(this, n, name);
			if (cont != ExpansionDebugHooks.PROCEED)
				return cont;
		}
		
		ResolveMagicWordLog log = null;
		if (frameLog != null)
		{
			log = new ResolveMagicWordLog(name, false);
			frameLog.getContent().add(log);
		}
		
		StopWatch stopWatch = null;
		if (timingEnabled)
		{
			stopWatch = new StopWatch();
			stopWatch.start();
		}
		
		WtNode result = null;
		try
		{
			result = resolvePageSwitch(n, name, log);
		}
		catch (Exception e)
		{
			result = markError(n, e);
			
			if (log != null)
				logUnhandledException(log, e);
			
			if (!catchAll)
				throw new ExpansionException(e);
		}
		finally
		{
			if (timingEnabled && log != null)
				log.setTimeNeeded(stopWatch.getElapsedTime());
		}
		
		return (hooks != null) ?
				hooks.afterResolvePageSwitch(this, n, name, result, log) :
				result;
	}
	
	private WtNode resolvePageSwitch(
			PageSwitch n,
			String name,
			ResolveMagicWordLog log)
	{
		ParserFunctionBase mw =
				getWikiConfig().getPageSwitch("__" + name + "__");
		
		if (mw == null)
			/* This should not happen: If you register a magic word with the
			 * parser (which only then will produce this magic word node)
			 * there also has to be a magic word object.
			 */
			throw new InternalError("Cannot find tag extension: " + name);
		
		WtNode result = mw.invoke(
				n,
				expFrame,
				Collections.<WtNode> emptyList());
		
		log.setSuccess(true);
		
		return result;
	}
	
	// =========================================================================
	
	public ExpansionFrame getExpFrame()
	{
		return expFrame;
	}
	
	public boolean isHadNewline()
	{
		return hadNewlineGlobal;
	}
	
	private WikiConfig getWikiConfig()
	{
		return expFrame.getWikiConfig();
	}
	
	private Compiler getCompiler()
	{
		return expFrame.getCompiler();
	}
	
	private WtNode getFrameArgument(String name)
	{
		return expFrame.getArguments().get(name);
	}
	
	private FullPage getWikitext(PageTitle title) throws Exception
	{
		return expFrame.getCallback().retrieveWikitext(expFrame, title);
	}
	
	private void logUnhandledException(WtContentNode log, Exception e)
	{
		StringWriter w = new StringWriter();
		e.printStackTrace(new PrintWriter(w));
		log.getContent().add(new UnhandledException(e, w.toString()));
	}
	
	private void fileInvalidPageNameWarning(WtNode n, String target)
	{
		expFrame.fileWarning(new InvalidPagenameWarning(
				WarningSeverity.FATAL,
				getClass(),
				n,
				target));
	}
	
	private void fileInvalidTemplateNameWarning(
			Template n,
			StringConversionException e)
	{
		expFrame.fileWarning(new InvalidNameWarning(
				WarningSeverity.FATAL,
				getClass(),
				n));
	}
	
	private void fileInvalidArgumentNameWarning(
			TemplateArgument n,
			StringConversionException e)
	{
		expFrame.fileWarning(new InvalidNameWarning(
				WarningSeverity.FATAL,
				getClass(),
				n));
	}
	
	private void fileInvalidParameterNameWarning(
			TemplateParameter n,
			StringConversionException e)
	{
		expFrame.fileWarning(new InvalidNameWarning(
				WarningSeverity.FATAL,
				getClass(),
				n));
	}
	
	private void filePageNotFoundWarning(WtNode n, PageTitle title)
	{
		expFrame.fileWarning(new PageNotFoundWarning(
				WarningSeverity.NORMAL,
				getClass(),
				n,
				title));
	}
	
	private WtNode mergeLogsAndWarnings(
			WtContentNode log,
			CompiledPage compiledPage)
	{
		if (log != null)
			log.getContent().add(compiledPage.getLog());
		
		expFrame.addWarnings(compiledPage.getWarnings());
		
		return compiledPage.getPage().getContent();
	}
	
	private WtNode treatBlockElements(Template tmpl, WtNode result)
	{
		if (result != null)
			return treatBlockElements(tmpl, result, tmpl.getPrecededByNewline());
		return null;
	}
	
	/*
	private WtNode treatBlockElements(TemplateParameter tmpl, WtNode result)
	{
		if (result != null)
			return treatBlockElements(tmpl, result, tmpl.getPrecededByNewline());
		return null;
	}
	*/
	
	/**
	 * If we don't had a newline before some template element but the template
	 * resolved to a block level element, THEN we prepend a newline to make the
	 * block level element start on a new line (so that it can be identified
	 * correctly).
	 */
	private WtNode treatBlockElements(
			WtNode tmpl,
			WtNode result,
			boolean hadNewline)
	{
		if (result != null && !hadNewline)
		{
			Tuple2<String, WtNodeList> split =
					StringConverterPartial.convert(result, null, StringConverter.FAIL_ON_PROTECTED_TEXT);
			
			Matcher m = STARTS_WITH_BLOCK_ELEMENT.matcher(split._1);
			if (m.find())
				result = astList(astText("\n" + split._1), split._2);
		}
		
		return result;
	}
	
	/**
	 * Returns if the expansion of the specified node should be skipped. This
	 * would for example be the case when we already tried to expand the node
	 * but failed.
	 */
	private boolean skip(WtNode n)
	{
		return n.hasAttribute(SKIP_ATTR_NAME);
	}
	
	/**
	 * Called when something could not be resolved. This could happen if a
	 * transclusion statement cannot find the target page. This method MUST NOT
	 * BE CALLED when the expansion process for this element failed due to an
	 * exception.
	 */
	private WtNode markError(WtNode n)
	{
		//return new SoftErrorNode(n);
		n.setAttribute(SKIP_ATTR_NAME, false);
		return n;
	}
	
	/**
	 * Called when the expansion process for the specified element failed due to
	 * an exception.
	 */
	private WtNode markError(WtNode n, Exception e)
	{
		//return new SoftErrorNode(n, e);
		n.setAttribute(SKIP_ATTR_NAME, e);
		return n;
	}
}
