/* 
 * This file is auto-generated.
 * DO NOT MODIFY MANUALLY!
 * 
 * Generated by AstNodeGenerator.
 * Last generated: 2012-09-26 11:07:49.
 */

package org.sweble.wikitext.parser.nodes;

/**
 * <h1>TableCell</h1> <h2>Grammar</h2>
 */
public class TableCell
		extends
			WtInnerNode2

{
	private static final long serialVersionUID = 1L;
	
	// =========================================================================
	
	public TableCell()
	{
		super(new WtNodeList(), new WtNodeList());
		
	}
	
	public TableCell(WtNodeList xmlAttributes, WtNodeList body)
	{
		super(xmlAttributes, body);
		
	}
	
	@Override
	public int getNodeType()
	{
		return org.sweble.wikitext.parser.AstNodeTypes.NT_TABLE_CELL;
	}
	
	// =========================================================================
	// Properties
	
	// =========================================================================
	// Children
	
	public final void setXmlAttributes(WtNodeList xmlAttributes)
	{
		set(0, xmlAttributes);
	}
	
	public final WtNodeList getXmlAttributes()
	{
		return (WtNodeList) get(0);
	}
	
	public final void setBody(WtNodeList body)
	{
		set(1, body);
	}
	
	public final WtNodeList getBody()
	{
		return (WtNodeList) get(1);
	}
	
	private static final String[] CHILD_NAMES = new String[] { "xmlAttributes", "body" };
	
	public final String[] getChildNames()
	{
		return CHILD_NAMES;
	}
	
	// =========================================================================
	
}
