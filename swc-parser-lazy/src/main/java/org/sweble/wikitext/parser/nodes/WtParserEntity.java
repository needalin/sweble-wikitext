package org.sweble.wikitext.parser.nodes;

import de.fau.cs.osr.ptk.common.ast.GenericText;
import de.fau.cs.osr.ptk.common.ast.RtData;

public class WtParserEntity
		extends
			GenericText<WtNode>
		implements
			WtNode
{
	
	private static final long serialVersionUID = 7333107598118095040L;
	
	// =========================================================================
	
	public WtParserEntity()
	{
	}
	
	public WtParserEntity(String content)
	{
		super(content);
	}
	
	// =========================================================================
	
	@Override
	public RtData setRtd(RtData rtd)
	{
		throw new UnsupportedOperationException();
	}
	
	@Override
	public RtData setRtd(Object... glue)
	{
		throw new UnsupportedOperationException();
	}
	
	@Override
	public RtData setRtd(String... glue)
	{
		throw new UnsupportedOperationException();
	}
	
	@Override
	public RtData getRtd()
	{
		return null;
	}
	
	@Override
	public void clearRtd()
	{
	}
	
}
