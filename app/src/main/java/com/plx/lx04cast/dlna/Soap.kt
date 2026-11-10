package com.plx.lx04cast.dlna

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** UPnP control messages: SOAP requests from control points and our responses. */
object Soap {
    /** An action call, e.g. AVTransport's "SetAVTransportURI" with its CurrentURI. */
    data class Action(val name: String, val args: Map<String, String>)

    /** A failed action; codes are UPnP's, e.g. 401 "Invalid Action", 701 "Transition not available". */
    class Fault(val code: Int, val description: String) : Exception(description)

    /**
     * The action in a SOAP [body]. The name comes from the body (the SOAPACTION header, e.g.
     * `"urn:schemas-upnp-org:service:AVTransport:1#Play"`, is the fallback): some senders
     * quote or format the header loosely.
     */
    fun parse(body: String, soapActionHeader: String?): Action? {
        val element = runCatching { firstBodyElement(body) }.getOrNull()
        val name = element?.localName ?: element?.tagName?.substringAfter(':')
            ?: soapActionHeader?.trim('"', ' ')?.substringAfter('#')?.takeIf { it.isNotBlank() }
            ?: return null
        val args = mutableMapOf<String, String>()
        val children = element?.childNodes
        if (children != null) {
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                args[child.localName ?: child.tagName.substringAfter(':')] = child.textContent.orEmpty()
            }
        }
        return Action(name, args)
    }

    fun response(serviceType: String, action: String, args: List<Pair<String, String>>): String = envelope(
        buildString {
            append("<u:${action}Response xmlns:u=\"$serviceType\">")
            args.forEach { (name, value) -> append("<$name>${escape(value)}</$name>") }
            append("</u:${action}Response>")
        }
    )

    fun fault(fault: Fault): String = envelope(
        "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>${fault.code}</errorCode>" +
            "<errorDescription>${escape(fault.description)}</errorDescription></UPnPError></detail></s:Fault>"
    )

    fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private fun envelope(body: String) = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>$body</s:Body></s:Envelope>"""

    private fun firstBodyElement(body: String): Element? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No DTDs or external entities: the body comes from the network.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(body.trim())))
        val bodies = document.getElementsByTagNameNS("*", "Body")
        val soapBody = (if (bodies.length > 0) bodies.item(0) else null) as? Element ?: return null
        val children = soapBody.childNodes
        for (i in 0 until children.length) {
            (children.item(i) as? Element)?.let { return it }
        }
        return null
    }
}
