/**
 * Copyright 2011 The Open Source Research Group,
 *                University of Erlangen-Nürnberg
 */
package org.sweble.wom3.impl;

import org.sweble.wom3.Wom3Attribute;
import org.sweble.wom3.Wom3Document;
import org.sweble.wom3.Wom3ElementNode;
import org.sweble.wom3.Wom3Node;
import org.w3c.dom.CDATASection;
import org.w3c.dom.DOMConfiguration;
import org.w3c.dom.DOMException;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.EntityReference;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;

public class DocumentImpl
		extends
			BackboneWithChildren
		implements
			Wom3Document
{
	private static final long serialVersionUID = 1L;
	
	private final DomImplementationImpl impl;
	
	private Wom3ElementNode root;
	
	/**
	 * As long as we don't support creating doctypes or appending them to this
	 * document, this variable will always be null.
	 */
	private final DocumentType doctype = null;
	
	private String documentUri;
	
	/**
	 * Always null for now.
	 */
	private final String xmlEncoding = null;
	
	/**
	 * Always null for now.
	 */
	private final String inputEncoding = null;
	
	private boolean strictErrorChecking = true;
	
	// =========================================================================
	
	public DocumentImpl()
	{
		this(DomImplementationImpl.get());
	}
	
	public DocumentImpl(DomImplementationImpl impl)
	{
		super(null);
		this.impl = impl;
	}
	
	// =========================================================================
	
	@Override
	public String getNodeName()
	{
		return "#document";
	}
	
	@Override
	public short getNodeType()
	{
		return Node.DOCUMENT_NODE;
	}
	
	@Override
	public Backbone getParentNode()
	{
		return null;
	}
	
	// =========================================================================
	// org.w3c.dom.Document - Getters
	
	@Override
	public DomImplementationImpl getImplementation()
	{
		return impl;
	}
	
	@Override
	public DocumentType getDoctype()
	{
		return doctype;
	}
	
	@Override
	public Wom3ElementNode getDocumentElement()
	{
		return root;
	}
	
	@Override
	public Element getElementById(String elementId)
	{
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}
	
	@Override
	public String getTextContent() throws DOMException
	{
		return null;
	}
	
	// =========================================================================
	// org.w3c.dom.Document - Document URI
	
	@Override
	public String getDocumentURI()
	{
		return documentUri;
	}
	
	@Override
	public void setDocumentURI(String documentURI)
	{
		this.documentUri = documentURI;
	}
	
	// =========================================================================
	// org.w3c.dom.Document - Creation
	
	@Override
	public AttributeImpl createAttribute(String name) throws DOMException
	{
		return new AttributeImpl(this, name);
	}
	
	@Override
	public AttributeImpl createAttributeNS(
			String namespaceURI,
			String qualifiedName) throws DOMException
	{
		return new AttributeNsImpl(this, namespaceURI, qualifiedName);
	}
	
	@Override
	public XmlCommentImpl createComment(String data)
	{
		return new XmlCommentImpl(this, data);
	}
	
	@Override
	public Wom3ElementNode createElement(String tagName) throws DOMException
	{
		return impl.createElement(this, tagName);
	}
	
	@Override
	public Wom3ElementNode createElementNS(
			String namespaceURI,
			String qualifiedName) throws DOMException
	{
		return impl.createElement(this, namespaceURI, qualifiedName);
	}
	
	@Override
	public XmlTextImpl createTextNode(String data)
	{
		return new XmlTextImpl(this, data);
	}
	
	@Override
	public CDATASection createCDATASection(String data) throws DOMException
	{
		return new CdataSection(this, data);
	}
	
	@Override
	public DocumentFragment createDocumentFragment()
	{
		return new DocumentFragmentImpl(this);
	}
	
	@Override
	public EntityReference createEntityReference(String name) throws DOMException
	{
		throw new UnsupportedOperationException();
	}
	
	@Override
	public ProcessingInstruction createProcessingInstruction(
			String target,
			String data) throws DOMException
	{
		throw new UnsupportedOperationException();
	}
	
	// =========================================================================
	// org.w3c.dom.Document - Node adoption
	
	@Override
	public Node adoptNode(Node source) throws DOMException
	{
		if (!isSameNode(source.getOwnerDocument()))
			adoptRecursively(source);
		
		if (source.getNodeType() == Node.ATTRIBUTE_NODE)
		{
			Wom3Attribute attr = (Wom3Attribute) source;
			if (attr.getOwnerElement() != null)
				attr.getOwnerElement().removeAttributeNode(attr);
		}
		else
		{
			if (source.getParentNode() != null)
				source.getParentNode().removeChild(source);
		}
		
		return source;
	}
	
	private void adoptRecursively(Node source_)
	{
		if (!(source_ instanceof Backbone))
			throw new UnsupportedOperationException();
		
		Backbone source = (Backbone) source_;
		switch (source.getNodeType())
		{
			case ATTRIBUTE_NODE:
				source.adoptTo(this);
				break;
			case ELEMENT_NODE:
				source.adoptTo(this);
				for (Wom3Node child : source.getWomChildNodes())
					adoptRecursively(child);
				break;
			case TEXT_NODE:
				source.adoptTo(this);
				break;
			
			case DOCUMENT_FRAGMENT_NODE:
			case ENTITY_REFERENCE_NODE:
				// Can be adopted (specifics!), only we don't ...
				// Fall through
				
			case PROCESSING_INSTRUCTION_NODE:
			case CDATA_SECTION_NODE:
			case COMMENT_NODE:
				// Can all be adopted (no specifics), only we don't ...
				// Fall through
				
			case DOCUMENT_NODE:
			case DOCUMENT_TYPE_NODE:
			case ENTITY_NODE:
			case NOTATION_NODE:
				// Cannot be adopted
				// Fall through
				
			default:
				throw new UnsupportedOperationException();
		}
	}
	
	@Override
	public Node importNode(Node importedNode, boolean deep) throws DOMException
	{
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}
	
	// =========================================================================
	// org.w3c.dom.Document - Error checking
	
	@Override
	public boolean getStrictErrorChecking()
	{
		return strictErrorChecking;
	}
	
	@Override
	public void setStrictErrorChecking(boolean strictErrorChecking)
	{
		this.strictErrorChecking = strictErrorChecking;
	}
	
	// =========================================================================
	// org.w3c.dom.Document - XML
	
	@Override
	public boolean getXmlStandalone()
	{
		return false;
	}
	
	@Override
	public String getXmlVersion()
	{
		return "1.1";
	}
	
	@Override
	public void setXmlStandalone(boolean xmlStandalone) throws DOMException
	{
		throw new UnsupportedOperationException();
	}
	
	@Override
	public void setXmlVersion(String xmlVersion) throws DOMException
	{
		throw new UnsupportedOperationException();
	}
	
	// =========================================================================
	// org.w3c.dom.Document - Encoding
	
	@Override
	public String getXmlEncoding()
	{
		return xmlEncoding;
	}
	
	@Override
	public String getInputEncoding()
	{
		return inputEncoding;
	}
	
	// =========================================================================
	// org.w3c.dom.Document - Document normalization
	
	@Override
	public DOMConfiguration getDomConfig()
	{
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}
	
	@Override
	public void normalizeDocument()
	{
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException();
	}
	
	// =========================================================================
	// org.w3c.dom.Document - Node renaming
	
	@Override
	public Node renameNode(Node n, String namespaceURI, String qualifiedName) throws DOMException
	{
		throw new UnsupportedOperationException();
	}
	
	// =========================================================================
	
	@Override
	public Wom3Node cloneNode(boolean deep)
	{
		throw new UnsupportedOperationException();
	}
	
	// =========================================================================
	
	@Override
	protected void allowsInsertion(Backbone prev, Backbone child)
	{
		if (prev == null && root == null && child instanceof Wom3ElementNode)
			return;
		doesNotAllowInsertion(prev, child);
	}
	
	@Override
	protected void allowsRemoval(Backbone child)
	{
	}
	
	@Override
	protected void allowsReplacement(Backbone oldChild, Backbone newChild)
	{
		if (oldChild == root && newChild instanceof Wom3ElementNode)
			return;
		doesNotAllowInsertion(oldChild, newChild);
	}
	
	@Override
	protected void childInserted(Backbone prev, Backbone added)
	{
		if (added instanceof Wom3ElementNode)
			root = (Wom3ElementNode) added;
	}
	
	@Override
	protected void childRemoved(Backbone prev, Backbone removed)
	{
		if (removed == root)
			root = null;
	}
	
	@Override
	public NodeList getElementsByTagName(String tagname)
	{
		// TODO: Implement
		throw new UnsupportedOperationException();
	}
	
	@Override
	public NodeList getElementsByTagNameNS(String namespaceURI, String localName)
	{
		// TODO: Implement
		throw new UnsupportedOperationException();
	}
}