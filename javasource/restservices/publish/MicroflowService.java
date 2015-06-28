package restservices.publish;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.HttpMethod;
import restservices.publish.RestServiceHandler.HandlerRegistration;
import restservices.publish.RestServiceRequest.RequestContentType;
import restservices.publish.RestServiceRequest.ResponseType;
import restservices.util.ICloseable;
import restservices.util.JSONSchemaBuilder;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.UriTemplate;
import restservices.util.Utils;
import system.proxies.FileDocument;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;

public class MicroflowService implements IRestServiceHandler{

	private String microflowname;
	private boolean hasArgument;
	private String argType;
	private boolean isReturnTypePrimitive;
	private String returnType;
	private String argName;
	private String roleOrMicroflow;
	private String description;
	private HttpMethod httpMethod;
	private boolean isFileSource = false;
	private boolean isFileTarget = false;
	
	private static final ServletFileUpload servletFileUpload = new ServletFileUpload(new DiskFileItemFactory(100000, new File(System.getProperty("java.io.tmpdir"))));
	private String relativeUrl;
	private HandlerRegistration serviceHandler;
	private ICloseable metaserviceHandler;
	private static final List<MicroflowService> microflowServices = Lists.newCopyOnWriteArrayList();

	public MicroflowService(String microflowname, String roleOrMicroflow, HttpMethod httpMethod,
			String pathTemplateString, String description) throws CoreException {
		checkNotNull(microflowname);
		checkNotNull(roleOrMicroflow);
		checkNotNull(httpMethod);
		
		this.microflowname = microflowname;
		this.roleOrMicroflow = roleOrMicroflow;
		this.description = description;
		this.httpMethod = httpMethod;
		
		if (pathTemplateString != null)
			this.relativeUrl = Utils.removeLeadingAndTrailingSlash(pathTemplateString);
		else
			this.relativeUrl = microflowname.split("\\.")[1].toLowerCase();
			
		this.consistencyCheck();

		register();
	}

	private void register() {
		unregister();
		
		microflowServices.add(this);
		serviceHandler = RestServiceHandler.registerServiceHandler(httpMethod, getRelativeUrl(), roleOrMicroflow, this);
		metaserviceHandler = RestServiceHandler.registerServiceHandlerMetaUrl(getRelativeUrl());
	}

	public void unregister() {
		microflowServices.remove(this);
		if (serviceHandler != null) {
			serviceHandler.close();
		}
		if (metaserviceHandler != null) {
			metaserviceHandler.close();
		}
	}

	private String getRelativeUrl() {
		return relativeUrl;
	}

	public MicroflowService(String microflowname, String securityRoleOrMicroflow, HttpMethod httpMethod, String description) throws CoreException {
		this(microflowname, securityRoleOrMicroflow, httpMethod, null, description);
	}

	private void consistencyCheck() throws CoreException {
		String secError = ConsistencyChecker.checkAccessRole(this.roleOrMicroflow);
		if (secError != null)
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname + ": " + secError);

		int argCount = Utils.getArgumentTypes(microflowname).size();

		if (argCount > 1)
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname + ", it should exist and have exactly zero or one argument");

		hasArgument = argCount == 1;
		
		List<String> pathParams = new UriTemplate(relativeUrl).getTemplateVariables();
		if (pathParams.size() > 0 && !hasArgument) {
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname + " with path '" + relativeUrl + ", the microflow should have a single input argument object with at least attributes " + pathParams);
		}
		
		if (hasArgument) {
			IDataType argtype = Utils.getFirstArgumentType(microflowname);
			if (!argtype.isMendixObject())
				throw new IllegalArgumentException("Cannot publish microflow " + microflowname + ", it should have a single object as input argument");
			this.argType = argtype.getObjectType();
			this.argName = Utils.getArgumentTypes(microflowname).keySet().iterator().next();
			isFileSource = Core.isSubClassOf(FileDocument.entityName, argType);

			
			IMetaObject metaObject = Core.getMetaObject(argType);
			if (metaObject.isPersistable() && !isFileSource)
				throw new IllegalArgumentException("Cannot publish microflow " + microflowname + ", it should have a transient object of filedocument as input argument");
			
			Set<String> metaPrimitiveNames = Sets.newHashSet();
			for(IMetaPrimitive prim : metaObject.getMetaPrimitives()) {
				metaPrimitiveNames.add(prim.getName().toLowerCase());
			}
			for(String pathParam : pathParams) {
				if (!metaPrimitiveNames.contains(pathParam.toLowerCase()))
					throw new IllegalArgumentException("Cannot publish microflow " + microflowname + ", its input argument should have an attribute with name '" + pathParam +"', as required by the template path");
			}
			
		}

		if (httpMethod == null) {
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname + ", it has no HTTP method defined.");
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

	@Override
	public void execute(final RestServiceRequest rsr, Map<String, String> params)	throws Exception {
		if (params.containsKey(RestServices.PARAM_ABOUT)) {
			serveDescription(rsr);
		}
		else {
			Map<String, Object> args = new HashMap<String, Object>();
			IMendixObject inputObject = parseInputData(rsr, params);
			
			if(inputObject != null) 
				args.put(argName, inputObject);
			
			if (isReturnTypePrimitive)
				rsr.setResponseContentType(ResponseType.PLAIN); //default, but might be overriden by the executing mf
			else if (isFileTarget)
				rsr.setResponseContentType(ResponseType.BINARY);
	
			Object result = Core.execute(rsr.getContext(), microflowname, args);
			writeOutputData(rsr, result);
		}
	}

	private void writeOutputData(RestServiceRequest rsr, Object result)
			throws IOException, Exception {
		if (result == null) {
			//write nothing
		}
		else if (this.isFileTarget) {
			if (!Utils.hasDataAccess(Core.getMetaObject(argType), rsr.getContext()))
				throw new IllegalStateException("Cannot serialize filedocument of type '" + argType + "', the object is not accessiable for users with role " + rsr.getContext().getSession().getUserRolesNames() + ". Please check the access rules");

			String filename = ((IMendixObject)result).getValue(rsr.getContext(), FileDocument.MemberNames.Name.toString());
			if (filename != null && !filename.isEmpty())
				rsr.response.setHeader(RestServices.HEADER_CONTENTDISPOSITION, "attachment;filename=\"" + Utils.urlEncode(filename) + "\"");
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
				rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), (IMendixObject) item, true));
			rsr.datawriter.endArray();
			rsr.endDoc();
		}
		else if (result instanceof IMendixObject) {
			rsr.startDoc();
			rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), (IMendixObject) result, true));
			rsr.endDoc();
		}
		else throw new IllegalStateException("Unexpected result from microflow " + microflowname + ": " + result.getClass().getName());
	}

	private IMendixObject parseInputData(RestServiceRequest rsr, Map<String, String> params)
			throws IOException, ServletException, Exception {
		if (!hasArgument)
			return null;
		
		if (!Utils.hasDataAccess(Core.getMetaObject(argType), rsr.getContext()))
			throw new IllegalStateException("Cannot instantiate object of type '" + argType + "', the object is not accessiable for users with role " + rsr.getContext().getSession().getUserRolesNames() + ". Please check the access rules");
		
		IMendixObject argObject = Core.instantiate(rsr.getContext(), argType);
		JSONObject data = new JSONObject();

		//multipart data
		if (rsr.getRequestContentType() == RequestContentType.MULTIPART) {
			parseMultipartData(rsr, argObject, data);
		}

		//json data
		else if (rsr.getRequestContentType() == RequestContentType.JSON || (rsr.getRequestContentType() == RequestContentType.OTHER && !isFileSource)) { 
			String body = IOUtils.toString(rsr.request.getInputStream());
			data = new JSONObject(StringUtils.isEmpty(body) ? "{}" : body);
		}

		//not multipart but expecting a file?
		else if (isFileSource) {
			Core.storeFileDocumentContent(rsr.getContext(), argObject, rsr.request.getInputStream());
		}

		RestServiceHandler.paramMapToJsonObject(params, data);
		
		//serialize to Mendix Object
		JsonDeserializer.readJsonDataIntoMendixObject(rsr.getContext(), data, argObject, false);
		
		return argObject;
	}

	private void parseMultipartData(RestServiceRequest rsr, IMendixObject argO,
			JSONObject data) throws IOException, FileUploadException {
		boolean hasFile = false;
		
		for(FileItemIterator iter = servletFileUpload.getItemIterator(rsr.request); iter.hasNext();) {
			FileItemStream item = iter.next();
			if (!item.isFormField()){ //This is the file(?!)
				if (!isFileSource) {
					RestServices.LOGPUBLISH.warn("Received request with binary data but input argument isn't a filedocument. Skipping. At: " + rsr.request.getRequestURL().toString());
					continue;
				}
				if (hasFile)
					RestServices.LOGPUBLISH.warn("Received request with multiple files. Only one is supported. At: " + rsr.request.getRequestURL().toString());
				hasFile = true;
				Core.storeFileDocumentContent(rsr.getContext(), argO, determineFileName(item), item.openStream());
			}
			else
				data.put(item.getFieldName(), IOUtils.toString(item.openStream()));
		}
	}

	private String determineFileName(FileItemStream item) {
		return null; //TODO:
	}

	public String getName() {
		return microflowname.split("\\.")[1].toLowerCase();
	}

	public String getRequiredRoleOrMicroflow() {
		return roleOrMicroflow;
	}

	public void serveDescription(RestServiceRequest rsr) {
		rsr.startDoc();

		if (rsr.getResponseContentType() == ResponseType.HTML)
			rsr.write("<h1>Operation: ").write(getRelativeUrl()).write("</h1>");

		rsr.datawriter.object()
			.key("name").value(getRelativeUrl())
			.key("description").value(description)
			.key("url").value(RestServices.getAbsoluteUrl(getRelativeUrl()))
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
	
	public String getHttpMethod() {
		return httpMethod == null ? null : httpMethod.toString();
	}

	public static void clearMicroflowServices() {
		while (!microflowServices.isEmpty())
			microflowServices.get(0).unregister();
	}
}
