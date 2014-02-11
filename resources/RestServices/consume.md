# Consuming webservices

REST style services are available in broad variety but the have one aspect in common: They can be invoked by using HTTP using common HTTP constructions to pass data, invalidate caches or perform authentication. The RestServices modules supports many common REST patterns. To set up a REST request, the following three steps need to be performed:

1. Setup authentication if required
2. Post request data
3. Process the request result. 

How this steps need to be executed specifically can typically be found in de API docs of the specific service. 

## Authentication
REST requests are executed by using the generic `request` method, or one of its specific overloads like `get`, `post`, `put`, `delete`, `postfile` etc.

Before such a request is made, authentication needs to be set up. In general, three common authentication methods are recognized. 

### Basic Authentication
Basic Authentication is the HTTP variant of sending a username / password to the server provider. Once you obtained a username and password (which is usually a manual process like performing a signup) you can pass those to the REST module by using either `addCredentialsToNextRequest` or `registerCredentials`. The latter variant requires a `urlBasePath` parameter. In contrast to the first, the credentials passed to this funcion will be applied automatically to all requests which are send to the specified basepath. 

### API keys
API keys are comparible with basic authentication, but instead of usign a fixed password/ username, you have to obtain a secret which typically is only valid for a single application and possibly only valid for a limited time. Usually, the API key needs to be send along with each request using some HTTP header, for example: `Api-Key` : `534sdf-slkl4-efes1`. To add headers to a request, use the `addHeaderToNextRequest` function before the actual request is executed. 

### OAuth
Oauth is a quite different authentication scheme. In contrast to the other methods, when using Oauth each user needs to authenticate the request your application made. This makes sure your application can execute requests 'in  behalf of' a certain user. Most social services, such as Dropbox, Twitter, Facebook and most Google Apps use the OAuth framework for authentication. 

Since OAuth is a framework, and not a protocol, no-ready-to-use implementation is available. Yet, OAuth can be setup using the REST module. However it is recommended to try to use a simpler authentication protocol first to get familiar with the RestServices module. 

When setting up OAuth typically the following steps must be followed. The goal of OAuth is to obtain some sort of 'AccessToken' that allows your application to access a service in behalf a specific user, usally for a limited amount of time and a limited scope. 

1. Setup your domain model to store access tokens which are connected to specific users
2. Obtain API keys to be able to talk the server. These API keys are necessary to obtain a access token later on. 
3. Obtain an access token for a specific user. This typically involves creating a link which the user can click to authorize your application. The link then redirects the user to the service you are trying to access, to allow him to authorize your applicaton to use that service. This link should state which resources you are trying to access and, very important, a `callback` that is used to bring the user back to your application. 