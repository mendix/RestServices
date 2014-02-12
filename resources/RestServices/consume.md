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

When setting up OAuth typically the following steps must be followed. The goal of OAuth is to obtain some sort of 'AccessToken' that allows your application to access a service in behalf a specific user, usally for a limited amount of time and a limited scope. The following flow describes in high level what a typical OAuth(2) authorization flow looks like. Note that the specific details are very service specific. The meaning, format and names of each request should be described at the service documentation in great detail. 

1. Setup your domain model to store access tokens which are connected to specific users
2. Obtain API keys to be able to talk the server. These API keys are necessary to obtain a access token later on. 
3. Obtain an access token for a specific user. This typically involves creating a link which the user can click to authorize your application. The link then redirects the user to the service you are trying to access, to allow him to authorize your applicaton to use that service. This link should state which resources you are trying to access and, very important, a `callback` that is used to bring the user back to your application. This callback should typically be picked up by a deeplink (see the Appstore Deeplink module) which receives some token or code. 
4. This token or code typically needs to be exchanged at the service provider for an access token. 
5. The resulting access token can be reused for any subsequent requests made for this user. Usually a HTTP header is used to send tokens to the providing services. 

## Sending a request

Sending requests is done by using the `request` function of one of its specific overloads; `get`, `post`, `put` or `delete`. When sending a request, provide the `method` and the `targetUrl` as you can find them in the service docs. If the service documentation does not state the method, then use `GET`. 

The targeturl should start with `http(s)://` and is further specified by the service. If the request method is GET or DELETE, additional parameters can be edited to this url by manually appending them, or by using the utility method `appendParamToUrl`. 

In most REST services, the following meaning is assigned to the method:

* GET - Requests a resouce described by the target url, for example: GET http://myapp.com/customer/1
* DELETE - Deletes the resource at the target url, for example: DELETE http://myapp.com/customer/1
* PUT - Create or update a resource described by the target url. PUT requests require a payload. For example: PUT http://myapp.com/customer/2
* POST - Create a new resource, within the target url's  namespace. This request requires a payload as well: POST http://myapp.com/customer. The response of a POST request should describe the newly created resource. 

### Request payload
POST and PUT request usually require a payload. The restservice module distinguishes three types of payloads:

1. JSON Data. To send complex JSON data, construct a transient object describing the whole JSON structure and pass it as requestData parameter. For a detailed explanation about (de)serializing JSON into transient objects see below. 
2. Form Data. Form data mimics the behavior of web browsers submitting data, and is often used if only simple parameters are required. Often form data and request parameters can be mixed and matched freely, meaning that you either send a parameter as part of the URL after the question mark, or as part of the body. Form Data can be submitted in the same way as JSON data, except that only primitive values can be send. Associated data will be ignored. To send data in form data format, set the parameter `asFormData` to true. 
3. Files. If a file document is passed to the `request` method, the whole file is streamed as binary data to the target url. If `asFormData` is set however, the file will be made part of a multipart form upload, and other members of the same document will be added as parameters to the request. 

### Interpreting the response 