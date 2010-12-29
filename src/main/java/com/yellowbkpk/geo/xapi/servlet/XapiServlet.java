package com.yellowbkpk.geo.xapi.servlet;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URLDecoder;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.antlr.runtime.RecognitionException;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.database.DatabaseLoginCredentials;
import org.openstreetmap.osmosis.core.database.DatabasePreferences;
import org.openstreetmap.osmosis.core.lifecycle.ReleasableIterator;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.PostgreSqlDatasetContext;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.Selection;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.Selector;
import org.openstreetmap.osmosis.pgsnapshot.v0_6.impl.Selector.BoundingBox;

import com.yellowbkpk.geo.xapi.XAPIQueryInfo;

public class XapiServlet extends HttpServlet {
	private static final DatabaseLoginCredentials loginCredentials = new DatabaseLoginCredentials("localhost", "xapi", "xapi", "xapi", true, false, null);
	private static final DatabasePreferences preferences = new DatabasePreferences(false, false);

	public void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		// Parse URL
		XAPIQueryInfo info = null;
		try {
			StringBuffer urlBuffer = request.getRequestURL();
			if (request.getQueryString() != null) {
				urlBuffer.append("?").append(request.getQueryString());
			}
			String reqUrl = urlBuffer.toString();
			String query = reqUrl.substring(reqUrl.lastIndexOf('/') + 1);
			query = URLDecoder.decode(query, "UTF-8");
			info = XAPIQueryInfo.fromString(query);
		} catch (RecognitionException e) {
			response.sendError(500, "Could not parse query: " + e.getMessage());
			return;
		}
		
		if(info.getBboxSelectors().size() > 1) {
			response.sendError(500, "Only one bounding box query is supported at this time.");
			return;
		}
		
		// Query DB
		ReleasableIterator<EntityContainer> bboxData;
		PostgreSqlDatasetContext datasetReader = new PostgreSqlDatasetContext(loginCredentials, preferences);
		if(XAPIQueryInfo.RequestType.NODE.equals(info.getKind())) {
			bboxData = datasetReader.iterateSelectedNodes(info.getBboxSelectors(), info.getTagSelectors());
		} else if(XAPIQueryInfo.RequestType.WAY.equals(info.getKind())) {
			bboxData = datasetReader.iterateSelectedWays(info.getBboxSelectors(), info.getTagSelectors());
		} else if(XAPIQueryInfo.RequestType.RELATION.equals(info.getKind())) {
			bboxData = datasetReader.iterateSelectedRelations(info.getBboxSelectors(), info.getTagSelectors());
		} else if(XAPIQueryInfo.RequestType.MAP.equals(info.getKind())) {
			BoundingBox boundingBox = info.getBboxSelectors().get(0);
			bboxData = datasetReader.iterateBoundingBox(boundingBox.getLeft(), boundingBox.getRight(), boundingBox.getTop(), boundingBox.getBottom(), true);
		} else {
			response.sendError(500, "Unsupported operation.");
			return;
		}
		
		// Build up a writer connected to the response output stream
		response.setContentType("text/xml; charset=utf-8");
		BufferedWriter out = new BufferedWriter(new OutputStreamWriter(response.getOutputStream()));
		
		// Serialize to the client
		Sink sink = new org.openstreetmap.osmosis.xml.v0_6.XmlWriter(out);
		
		try {
			while (bboxData.hasNext()) {
				sink.process(bboxData.next());
			}
			
			sink.complete();
			
		} finally {
			bboxData.release();
		}
		
		out.flush();
		out.close();
	}
}
