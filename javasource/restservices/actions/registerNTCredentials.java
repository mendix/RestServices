// This file was generated by Mendix Modeler.
//
// WARNING: Only the following code will be retained when actions are regenerated:
// - the import list
// - the code between BEGIN USER CODE and END USER CODE
// - the code between BEGIN EXTRA CODE and END EXTRA CODE
// Other code you write will be lost the next time you deploy the project.
// Special characters, e.g., é, ö, à, etc. are supported in comments.

package restservices.actions;

import restservices.consume.RestConsumer;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.webui.CustomJavaAction;

public class registerNTCredentials extends CustomJavaAction<java.lang.Boolean>
{
	private java.lang.String urlBasePath;
	private java.lang.String username;
	private java.lang.String password;
	private java.lang.String domain;

	public registerNTCredentials(IContext context, java.lang.String urlBasePath, java.lang.String username, java.lang.String password, java.lang.String domain)
	{
		super(context);
		this.urlBasePath = urlBasePath;
		this.username = username;
		this.password = password;
		this.domain = domain;
	}

	@Override
	public java.lang.Boolean executeAction() throws Exception
	{
		// BEGIN USER CODE
		RestConsumer.registerNTCredentials(urlBasePath, username, password, domain);
		return true;
		// END USER CODE
	}

	/**
	 * Returns a string representation of this action
	 */
	@Override
	public java.lang.String toString()
	{
		return "registerNTCredentials";
	}

	// BEGIN EXTRA CODE
	// END EXTRA CODE
}