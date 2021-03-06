package io.rtdi.bigdata.fileconnector.rest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.univocity.parsers.csv.CsvFormat;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;

import io.rtdi.bigdata.connector.connectorframework.WebAppController;
import io.rtdi.bigdata.connector.connectorframework.controller.ConnectionController;
import io.rtdi.bigdata.connector.connectorframework.controller.ConnectorController;
import io.rtdi.bigdata.connector.connectorframework.exceptions.ConnectorCallerException;
import io.rtdi.bigdata.connector.connectorframework.rest.JAXBErrorResponseBuilder;
import io.rtdi.bigdata.connector.connectorframework.servlet.ServletSecurityConstants;
import io.rtdi.bigdata.connector.pipeline.foundation.exceptions.PropertiesException;
import io.rtdi.bigdata.fileconnector.FileConnectionProperties;
import io.rtdi.bigdata.fileconnector.entity.EditSchemaData;
import io.rtdi.bigdata.fileconnector.entity.EditSchemaData.ColumnDefinition;

@Path("/")
public class FilePreviewService {
	@Context
    private Configuration configuration;

	@Context 
	private ServletContext servletContext;

	public FilePreviewService() {
	}
			
	@POST
	@Path("/files/raw/{connectionname}/{filename}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed({ServletSecurityConstants.ROLE_VIEW})
    public Response getRawFileContent(@PathParam("connectionname") String connectionname, @PathParam("filename") String filename, EditSchemaData data) {
		try {
			File file = getFile(servletContext, connectionname, filename);
			return Response.ok(new Filedata(file, getCharset(data))).build();
		} catch (Exception e) {
			return JAXBErrorResponseBuilder.getJAXBResponse(e);
		}
	}

	@POST
	@Path("/files/parsed/{connectionname}/{filename}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed({ServletSecurityConstants.ROLE_VIEW})
    public Response getParsedFileContent(@PathParam("connectionname") String connectionname, @PathParam("filename") String filename, EditSchemaData data) {
		try {
			File file = getFile(servletContext, connectionname, filename);
			return Response.ok(new ParsedData(file, data)).build();
		} catch (Exception e) {
			return JAXBErrorResponseBuilder.getJAXBResponse(e);
		}
	}

	@POST
	@Path("/files/guess/{connectionname}/{filename}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed({ServletSecurityConstants.ROLE_VIEW})
    public Response getGuessFormat(@PathParam("connectionname") String connectionname, @PathParam("filename") String filename, EditSchemaData format) {
		try {
			File file = getFile(servletContext, connectionname, filename);
			try (FileInputStream in = new FileInputStream(file); ) {
				CsvParserSettings settings = format.getSettings();
				format.setColumns(null);
				settings.detectFormatAutomatically();
				settings.setNumberOfRecordsToRead(30);
				CsvParser parser = new CsvParser(settings);
				List<String[]> rows = parser.parseAll(in);
				CsvFormat detectedFormat = parser.getDetectedFormat();
				settings.setFormat(detectedFormat);
				
				String[] cols = parser.getRecordMetadata().headers();
				format.updateHeader(cols, rows, true);
			}
			return Response.ok(format).build();
		} catch (Exception e) {
			return JAXBErrorResponseBuilder.getJAXBResponse(e);
		}
	}

	public static File getFile(ServletContext servletContext, String connectionname, String filename) throws PropertiesException {
		ConnectorController connector = WebAppController.getConnectorOrFail(servletContext);
		ConnectionController connection = connector.getConnectionOrFail(connectionname);
		FileConnectionProperties props = (FileConnectionProperties) connection.getConnectionProperties();
		String path = props.getRootDirectory() + File.separatorChar + filename;
		File file = new File(path);
		if (!file.exists()) {
			throw new ConnectorCallerException("file not found", null, "The requested file does not exist", path);
		} else if (!file.isFile()) {
			throw new ConnectorCallerException("path exists but is no file", null, "The requested path points to a directory", path);
		} else {
			return file;
		}
	}
	
	public static Charset getCharset(EditSchemaData data) {
		if (data != null && data.getCharset() != null) {
			return Charset.forName(data.getCharset());
		} else {
			return Charset.defaultCharset();
		}
	}
	
	public static class ParsedData {
		private List<ColumnDefinition> columns;
		private List<Map<String, String>> rows;

		public ParsedData() {
		}

		public ParsedData(File file, EditSchemaData format) throws IOException {
			try (FileInputStream in = new FileInputStream(file); ) {
				CsvParserSettings settings = format.getSettings();
				settings.setNumberOfRecordsToRead(30);
				
				settings.setHeaders((String[]) null); // The parser defines the columns and is not using them
				CsvParser parser = new CsvParser(settings);
				List<String[]> result = parser.parseAll(in, getCharset(format));
				String[] cols = parser.getRecordMetadata().headers();
				format.updateHeader(cols, result, false);
				columns = format.getColumns();
				if (result != null) {
					rows = new ArrayList<>();
					for (String[] row : result) {
						Map<String, String> value = new HashMap<>();
						for (int i=0; i<row.length && i<columns.size(); i++) {
							if (row[i] != null) {
								value.put(columns.get(i).getName(), row[i]);
							}
						}
						rows.add(value);
					}
				}

			}
		}

		public List<ColumnDefinition> getColumns() {
			return columns;
		}

		public List<Map<String, String>> getRows() {
			return rows;
		}

	}
	
	public static class Filedata {
		private String data;
		/**
		 * The ByteOrderMark is a leading char in the file for Unicode indicating the exact encoding 
		 */
		private String foundbom;

		public Filedata() {
		}
		
		public Filedata(File file, Charset charset) throws IOException {
			try (FileInputStream in = new FileInputStream(file); ) {
				// When no format has been defined yet, dump out the data using the specified codepage
				int ret;
				CharBuffer charbuffer = CharBuffer.allocate(1024*30);
				try (InputStreamReader reader = new InputStreamReader(in, charset); ) {
					while (charbuffer.remaining() > 0 && (ret = reader.read(charbuffer.array(), charbuffer.position(), charbuffer.remaining())) != -1) {
						charbuffer.position(charbuffer.position()+ret);
					}
					data = new String(charbuffer.array(), 0, charbuffer.position()).replace("\r", "\\r").replace("\n", "\\n\n"); // make \r and \n visible
				}
			}
		}

		public String getFoundbom() {
			return foundbom;
		}
		
		public String getSampledata() {
			return data;
		}
	}
	
}
	