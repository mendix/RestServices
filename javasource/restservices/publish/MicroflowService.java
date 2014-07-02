package restservices.publish;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.http.Part;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.util.MultiPartInputStream.MultiPart;
import org.glassfish.jersey.uri.UriTemplate;
import org.json.JSONObject;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import restservices.RestServices;
import restservices.publish.RestServiceRequest.RequestContentType;
import restservices.publish.RestServiceRequest.ResponseType;
import restservices.util.JSONSchemaBuilder;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.Utils;
import restservices.util.Utils.IRetainWorker;
import system.proxies.FileDocument;

public class MicroflowService {

	private String microflowname;
	private boolean hasArgument;
	private String argType;
	private boolean isReturnTypePrimitive;
	private String returnType;
	private String argName;
	private String securityRoleOrMicroflow;
	private String description;
	private String httpMethod;
	private UriTemplate pathTemplate;
	private boolean isFileSource = false;
	private boolean isFileTarget = false;

	public MicroflowService(String microflowname, String securityRoleOrMicroflow, String description,
			String httpMethod, String pathTemplateString) throws CoreException {
		this.microflowname = microflowname;
		this.securityRoleOrMicroflow = securityRoleOrMicroflow;
		this.description = description;
		this.httpMethod = httpMethod;
		if(pathTemplateString != null) this.pathTemplate = new UriTemplate(pathTemplateString);
		this.consistencyCheck();
		RestServices.registerPublishedMicroflow(this);
	}
	
	public MicroflowService(String microflowname, String securityRoleOrMicroflow, String description) throws CoreException {
		this(microflowname, securityRoleOrMicroflow, description, null, null);
	}

	private void consistencyCheck() throws CoreException {
		String secError = ConsistencyChecker.checkAccessRole(this.securityRoleOrMicroflow);
		if (secError != null)
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname + ": " + secError);

		int argCount = Utils.getArgumentTypes(microflowname).size();

		if (argCount > 1)
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", it should exist and have exactly zero or one argument");

		hasArgument = argCount == 1;
		
		if (hasArgument) {
			IDataType argtype = Utils.getFirstArgumentType(microflowname);
			if (!argtype.isMendixObject())
				throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", it should have a single object as input argument");
			this.argType = argtype.getObjectType();
			this.argName = Utils.getArgumentTypes(microflowname).keySet().iterator().next();
			isFileSource = Core.isSubClassOf(FileDocument.entityName, argType);

			if (Core.getMetaObject(argType).isPersistable() && !isFileSource)
				throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", it should have a transient object of filedocument as input argument");
			
			if(pathTemplate != null && httpMethod == null)
				throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", it has a path template but no HTTP method defined.");
		}

		IDataType returnTypeFromMF = Core.getReturnType(microflowname);
		
		if (returnTypeFromMF.isMendixObject() || returnTypeFromMF.isList()){
			this.returnType = returnTypeFromMF.getObjectType();
			isFileTarget = Core.isSubClassOf(FileDocument.entityName, this.returnType); 

			if (Core.getMetaObject(this.returnType).isPersistable()  && !isFileTarget)
				throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", its return type should be a non-persistable object or a file document");
		}
		else
			isReturnTypePrimitive = true;
	}

	void execute(final RestServiceRequest rsRequest) throws Exception {
		Map<String, Object> args = new HashMap<String, Object>();
		IMendixObject inputObject = parseInputData(rsRequest);
		
		if(inputObject != null) args.put(argName, inputObject);
		
		if (isReturnTypePrimitive)
			rsRequest.setResponseContentType(ResponseType.PLAIN); //default, but might be overriden by the executing mf
		else if (isFileTarget)
			rsRequest.setResponseContentType(ResponseType.BINARY);

		Object result = Core.execute(rsRequest.getContext(), microflowname, args);

		Utils.whileRetainingObject(rsRequest.getContext(), result, new IRetainWorker<Boolean>() {

			@Override
			public Boolean apply(Object item) throws IOException, Exception {
				writeOutputData(rsRequest, item);
				return true;
			}
		});
	}

	private void writeOutputData(RestServiceRequest rsr, Object result)
			throws IOException, Exception {
		if (result == null) {
			//write nothing
		}
		else if (this.isFileTarget) {
			if (!Utils.hasDataAccess(Core.getMetaObject(argType), rsr.getContext()))
				throw new IllegalStateException("Cannot serialize filedocument of type '" + argType + "', the object is not accessiable for users with role " + rsr.getContext().getSession().getUserRolesNames() + ". Please check the access rules");

			
			InputStream stream  = Core.getFileDocumentContent(rsr.getContext(), (IMendixObject)result);
			IOUtils.copy(stream, rsr.response.getOutputStream());
		}
		else if (this.isReturnTypePrimitive) {
			rsr.write(result == null ? "" : String.valueOf(result));
		}
		else if (result instanceof List<?>) {
			rsr.startDoc();

			rsr.datawriter.array();
			for(Object item : (List<?>)result)
				rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), (IMendixObject) item));
			rsr.datawriter.endArray();
			rsr.endDoc();
		}
		else if (result instanceof IMendixObject) {
			rsr.startDoc();
			rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), (IMendixObject) result));
			rsr.endDoc();
		}
		else throw new IllegalStateException("Unexpected result from microflow " + microflowname + ": " + result.getClass().getName());
	}

	private IMendixObject parseInputData(RestServiceRequest rsRequest)
			throws IOException, ServletException, Exception {
		if (!hasArgument)
			return null;
		
		if (!Utils.hasDataAccess(Core.getMetaObject(argType), rsRequest.getContext()))
			throw new IllegalStateException("Cannot instantiate object of type '" + argType + "', the object is not accessiable for users with role " + rsRequest.getContext().getSession().getUserRolesNames() + ". Please check the access rules");
		
		IMendixObject argObject = Core.instantiate(rsRequest.getContext(), argType);
		JSONObject data = new JSONObject();

		//multipart data
		if (rsRequest.getRequestContentType() == RequestContentType.MULTIPART) {
			parseMultipartData(rsRequest, argObject, data);
		}

		//json data
		else if (rsRequest.getRequestContentType() == RequestContentType.JSON || (rsRequest.getRequestContentType() == RequestContentType.OTHER && !isFileSource)) { 
			String body = IOUtils.toString(rsRequest.request.getInputStream());
			data = new JSONObject(StringUtils.isEmpty(body) ? "{}" : body);
		}

		//not multipart but expecting a file?
		else if (isFileSource) {
			Core.storeFileDocumentContent(rsRequest.getContext(), argObject, rsRequest.request.getInputStream());
		}

		//read request parameters (this picks up form encoded data as well)
		RestServiceHandler.requestParamsToJsonMap(rsRequest, data);

		// request path parameters
		if(pathTemplate != null) {
			Map<String, String> pathValues = new HashMap<String, String>();
			pathTemplate.match(rsRequest.getPath(), pathValues);
	
			for(Entry<String, String> pathValue : pathValues.entrySet())
				data.put(pathValue.getKey(), pathValue.getValue());
		}
		
		//serialize to Mendix Object
		JsonDeserializer.readJsonDataIntoMendixObject(rsRequest.getContext(), data, argObject, false);
		
		return argObject;
	}

	private void parseMultipartData(RestServiceRequest rsr, IMendixObject argO,
			JSONObject data) throws IOException, ServletException {
		boolean hasFile = false;

		for(Part part : rsr.request.getParts()) {
			String filename = ((MultiPart)part).getContentDispositionFilename();
			if (filename != null) { //This is the file(?!)
				if (!isFileSource) {
					RestServices.LOGPUBLISH.warn("Received request with binary data but input argument isn't a filedocument. Skipping. At: " + rsr.request.getRequestURL().toString());
					continue;
				}
				if (hasFile)
					RestServices.LOGPUBLISH.warn("Received request with multiple files. Only one is supported. At: " + rsr.request.getRequestURL().toString());
				hasFile = true;
				Core.storeFileDocumentContent(rsr.getContext(), argO, filename, part.getInputStream());
			}
			else
				data.put(part.getName(), IOUtils.toString(part.getInputStream()));
		}
	}

	public String getName() {
		return microflowname.split("\\.")[1].toLowerCase();
	}

	public String getRequiredRoleOrMicroflow() {
		return securityRoleOrMicroflow;
	}

	public void serveDescription(RestServiceRequest rsr) {
		rsr.startDoc();

		if (rsr.getResponseContentType() == ResponseType.HTML)
			rsr.write("<h1>Operation: ").write(getName()).write("</h1>");

		rsr.datawriter.object()
			.key("name").value(getName())
			.key("description").value(description)
			.key("url").value(RestServices.getServiceUrl(getName()))
			.key("arguments").value(
					hasArgument
					? JSONSchemaBuilder.build(Utils.getFirstArgumentType(microflowname))
					: null
			)
			.key("accepts_binary_data").value(isFileSource)
			.key("result").value(isFileTarget
					? RestServices.CONTENTTYPE_OCTET + " stream"
					: JSONSchemaBuilder.build(Core.getReturnType(microflowname)))
			.endObject();

		rsr.endDoc();
	}
	
	public String getVerb() {
		return httpMethod;
	}
	
	public UriTemplate getPathTemplate() {
		return pathTemplate;
	}
}
