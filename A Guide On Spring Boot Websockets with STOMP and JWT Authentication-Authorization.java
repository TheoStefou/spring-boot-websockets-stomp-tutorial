/* 
  If you are using Spring Security, you may have to slightly change your
  WebSecurityConfigurerAdapter in order to allow the endpoints that STOMP
  uses by default.

  For example, within your antMatchers logic, in order to fix this, add the following:

  http.csrf().disable()
    .authorizeRequests()
    ...
    ... Your antMatchers go here
    ...
    .antMatchers("/our-websocket/**").permitAll() <-------------- This endpoint (and all below it) as you will see later is used by STOMP before upgrading the http connection to a websocket connection
    .anyRequest().authenticated()
    .and().sessionManagement()
    .sessionCreationPolicy(SessionCreationPolicy.STATELESS);
*/



/*
  Create a configuration class for Websockets:

  /our-websocket will be the endpoint where js will initially try to connect
  e.g.  var socket = new SockJS('http://localhost:8080/our-websocket');
        var stompClient = Stomp.over(socket);

        and to connect:

        stompClient.connect({Authorization: 'your jwt'}, function(frame) { ... once connected, subscribe to desired topics ... })

  
  /topic below this live all the subscribable endpoints to which clients may listen
  
  e.g. If a client wishes to listen to the topic "message", they would have to do the following:
  
  stompClient.subscribe('/topic/messages', function (message) {
      console.log(JSON.parse(message.body).content);
  });

  /ws below this live all the destinations of the server to which messages can be sent

  e.g. If a client wishes to send a message to the server to the topic 'message', the would have to do the following:
  
  stompClient.send("/ws/message", {}, JSON.stringify({'messageContent': 'message content...'}));


  If a users wants to subscribe to a personal topic at /private-messages, then that has to be prefixed with /user

  e.g.

  stompClient.subscribe('/user/topic/private-messages', function (message) {
      console.log(JSON.parse(message.body).content);
  });

*/
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    MyChannelInterceptor myChannelInterceptor;

    @Override
    public void configureMessageBroker(final MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/ws");
    }

    @Override
    public void registerStompEndpoints(final StompEndpointRegistry registry) {
        registry.addEndpoint("/our-websocket")
                //.setHandshakeHandler(new UserHandshakeHandler()) <- Add this if you want control of the HttpHandshake that happens before upgrading to web socket. More info at the end of the file.
                .setAllowedOriginPatterns("*")
                .withSockJS();

        // This will handle errors like unauthenticated or unauthorized access
        registry.setErrorHandler(new MyStompSubProtocolErrorHandler());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // This includes all the logic behind who gets to subscribe/connect
        registration.interceptors(myChannelInterceptor);
    }
}


/*
  Create a channel interceptor implementation class and override the preSend method.
  This method is called every time a STOMP message arrives. You will have to check the
  message headers and information to make sure the user is allowed to 1) connect and
  2) subscribe to a particular topic.
*/

@Component
public class MyChannelInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {

        MessageHeaders headers = message.getHeaders();
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        StompCommand command = accessor != null ? accessor.getCommand() : null;
        String sessionId = accessor != null ? accessor.getSessionId() : null;

        if(command != null) {
            
            if (StompCommand.CONNECT.equals(command)) {
                
                String jwtToken = accessor.getFirstNativeHeader("Authorization");
                
                if(jwtToken == null) {
                    // It's best if you create a custom exception that extends Runtime, like MyNotAuthenticatedException extends RuntimeException
                    throw new RuntimeException("Not authenticated!");
                }

                String username = // myAuthService.getUsername(jwtToken);

                // Set the user principal. From now on, subsequent calls to preSend will have access to this by calling accessor.getUser().getName()
                accessor.setUser(new Principal() {
                    @Override
                    public String getName() {
                        return username;
                    }
                });

            }
            else if(StompCommand.SUBSCRIBE.equals(command)) {
                
                MultiValueMap<String, String> multiValueMap = headers.get(StompHeaderAccessor.NATIVE_HEADERS, MultiValueMap.class);
                Principal principal = accessor.getUser(); // The user that was set in the previous CONNECT command
                String topic = multiValueMap.getFirst("destination"); // The topic to which the user wants to subscribe

                if(topic != null) {

                  if(/* user not allowed on topic: Custom logic that tokenizes the topic the decides if the user may subscribe. */) {
                    
                    // It's best if you create a custom exception that extends Runtime, like MyNotAuthorizedException extends RuntimeException
                    throw new RuntimeException("Not authorized to subscribe to " + topic);
                  }
                }
            }
            else if(StompCommand.SEND.equals(command)) {

              /* Write your logic here that will prevent users from sending messages to topics they are not allowed. */

            }
        }

        return message;
    }
}

/*
  Create the MyStompSubProtocolErrorHandler class that will handle the errors. Basically when an exception is thrown in preSend,
  this class will handle it in its handleInternal method. Because we are dealing with unathenticated and unauthorized access which
  cann be possiby dangerous, we will send back a STOMP message with the ERROR header, so that the server authomatically closes the
  connection.
*/

public class MyStompSubProtocolErrorHandler extends StompSubProtocolErrorHandler {

    @Override
    protected Message<byte[]> handleInternal(StompHeaderAccessor errorHeaderAccessor, byte[] errorPayload, Throwable cause, StompHeaderAccessor clientHeaderAccessor) {
        
        /* You may return a custom message, as well as custom headers. Each key can have multiple values, so we need a Map: String -> List<String> */
        Map<String, List<String>> headers = new HashMap<>();
        List<String> L = new ArrayList<>();
        L.add("SomeValue");
        
        headers.put("MyHeader", L);

        StompHeaderAccessor headerAccessor = StompHeaderAccessor.create(StompCommand.ERROR, headers);

        // If you want access to the string of the exception that was thrown in preSend, you can call: cause.getCause().getMessage()

        if(cause.getCause() instanceof /* Your custom class type that was thrown when CONNECT failed */) {
            return MessageBuilder.createMessage("You are not authenticated.".getBytes(StandardCharsets.UTF_8), headerAccessor.getMessageHeaders());
        }
        else if(cause.getCause() instanceof /* Your custom class type that was thrown when SUBSCRIBE failed */) {
            return MessageBuilder.createMessage("You are not authorized to subscribe to this topic.".getBytes(StandardCharsets.UTF_8), headerAccessor.getMessageHeaders());
        }
        else if(cause.getCause() instanceof /* Your custom class type that was thrown when SEND failed */) {
          return MessageBuilder.createMessage("You are not authorized to send messages to this topic.".getBytes(StandardCharsets.UTF_8), headerAccessor.getMessageHeaders());
        }

    }
}


/* Your services can send messages to topics and/or specific users. */

@Service
public class NotificationService {
    
    @Autowired
    private final SimpMessagingTemplate messagingTemplate; // Using this object you can send messages to websockets

    public void sendGlobalNotification() {

        ResponseMessage message = new ResponseMessage("Global Notification");

        // This will send a message to clients subscribed to /topic/global-notifications
        messagingTemplate.convertAndSend("/topic/global-notifications", message);
    }

    public void sendPrivateNotification(final String userId) {
        ResponseMessage message = new ResponseMessage("Private Notification");

        // This will send a message to a client with Principal.name = userId subscribed to /user/topic/private-notifications
        messagingTemplate.convertAndSendToUser(userId,"/topic/private-notifications", message);
    }
}

/* ResponseMessage is a simple POJO class */

public class ResponseMessage {
    private String content;

    public ResponseMessage() {
    }

    public ResponseMessage(String content) {
        this.content = content;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}


/* Just like RestControllers, you can define controllers for websockets */

@Controller
public class MessageController {


    /*
      This is listening for messages that will be published by clients to /ws/message
      and will publish a ResponseMessage to /topic/messages
    */

    @MessageMapping("/message")
    @SendTo("/topic/messages")
    public ResponseMessage getMessage(final Message message, Principal principal) throws InterruptedException {
        return new ResponseMessage(HtmlUtils.htmlEscape(message.getMessageContent()));
    }


    /*
      This is listening for messages that will be published by clients to /ws/private-message
      and will publish a ResponseMessage to /user/topic/private-message.
      Thi is not very useful, as it will just send the message back to the client that sent it.
    */
    @MessageMapping("/private-message")
    @SendToUser("/topic/private-messages")
    public ResponseMessage getPrivateMessage(final Message message,
                                             final Principal principal) throws InterruptedException {
        return new ResponseMessage(HtmlUtils.htmlEscape(
                "Sending private message to user " + principal.getName() + ": "
                        + message.getMessageContent())
        );
    }
}


/*
    If you want to have private chat functionality, you could define a classic RestController.
    You can also utilize the NotificationService that we defined above.
*/

@RestController
public class WSController {


    @Autowired
    private final SimpMessagingTemplate messagingTemplate;

    // This will notify everyone in /topic/messages after receiving an HTTP POST request at /send-message
    
    @PostMapping("/send-message")
    public void sendMessage(@RequestBody final Message message) {
        ResponseMessage response = new ResponseMessage(message.getMessageContent());

        messagingTemplate.convertAndSend("/topic/messages", response);
    }


    // This will notify a specific user with principal=id who is listening to /user/topic/private-messages after receiving an HTTP POST request at /send-private-message/{id}

    @PostMapping("/send-private-message/{id}")
    public void sendPrivateMessage(@PathVariable final String id,
                                   @RequestBody final Message message) {
        ResponseMessage response = new ResponseMessage(message.getMessageContent());

        messagingTemplate.convertAndSendToUser(id, "/topic/private-messages", response);
    }
}

/* Where Message is just a DTO */
public class Message {
    private String messageContent;

    public String getMessageContent() {
        return messageContent;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = messageContent;
    }
}


/*
  Our WebSocketConfig class executes registry.enableSimpleBroker("/topic")
  This enables an in-memory broker that handles the messages, but it might not be ideal
  for a production application with many users. If that is the case, spring boot can be
  configured to use an external broker like RabbitMQ. I have not tested this but it seems
  to be quite easy. In your application.properties, provide the properties required by the fields
  of the following class and change the code to the following.
*/

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    
    @Value("${spring.rabbitmq.username}")
    private String userName;
    
    @Value("${spring.rabbitmq.password}")
    private String password;
    
    @Value("${spring.rabbitmq.host}")
    private String host;
    
    @Value("${spring.rabbitmq.port}")
    private int port;
    
    @Value("${endpoint}")
    private String endpoint;
    
    @Value("${destination.prefix}")
    private String destinationPrefix;
    
    @Value("${stomp.broker.relay}")
    private String stompBrokerRelay;
    @Override
    public void configureMessageBroker(final MessageBrokerRegistry config) {
        config.enableStompBrokerRelay(stompBrokerRelay).setRelayHost(host).setRelayPort(port).setSystemLogin(userName).setSystemPasscode(password);
        config.setApplicationDestinationPrefixes(destinationPrefix);
    }
    @Override
    public void registerStompEndpoints(final StompEndpointRegistry registry) {
        registry
          .addEndpoint(endpoint)
          .setAllowedOriginPatterns("*")
          .withSockJS();
    }
}


/*
    Sometimes it is useful to have the server-side ability to disconnect all the sessions of a particular user.
    This is a bit trickier, but can be implemented easily as follows.
    First of all create a WebSocketSessionsService.
*/

@Service
public class WebSocketSessionService {

    // Map: username -> list of websocket sessions (all the sessions that belong to the user)
    ConcurrentHashMap<String, List<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    
    // All the sessions that we have received at the server, but whose identity we don't know yet
    ConcurrentHashMap<String, WebSocketSession> unclaimed = new ConcurrentHashMap<>();

    public void addToSessions(String username, WebSocketSession webSocketSession) {
        /*  Write code to make a new entry in sessions. If the key username is not found, insert a list containing just that element.
            Otherwise, add the WebsocketSession to the List of the username.
            Something like: sessions.get(username).add(webSocketSession), but take care to implement this atomically with the ConcurrentHashMap.
        */
    }

    public void closeAndRemoveFromSessions(String username) {
        /*  Write code to remove the value for the key=username from sessions and call WebSocketSession.close()
            on every single item of the corresponding list. Something like the following could work.
        */

        List<WebSocketSession> L = sessions.remove(username);

        L.stream().forEach(i -> {
            try {
                i.close();
            } catch (IOException e) {
                System.out.println("Failed to close. Continuing.");
            }
        });
    }

    public void addToUnclaimed(String webSocketId, WebSocketSession session) {
        unclaimed.put(webSocketId, session);
    }

    public void moveUnclaimedToSessions(String webSocketId, String username) {
        /*  Write code to transfer a (socketId, webSocketSession) pair from the unclaimed map to the
            sessions map for the given username. You can use this.addToSessions for the latter.
        */
    }
}

/*
    Now we have basically create an in-memory repository of usernames and their corresponding websocket sessions.
    In our WebSocketConfig class, we also override the configureWebSocketTransport method and write code to insert
    a websocket session to the unclaimed sessions of WebSocketSessionService.
*/

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    WebSocketSessionService webSocketSessionService;

    @Autowired
    MyChannelInterceptor myChannelInterceptor;

    
    /* ... Methods we implemented earlier ... */

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.addDecoratorFactory(new WebSocketHandlerDecoratorFactory() {
            
            @Override
            public WebSocketHandler decorate(WebSocketHandler handler) {
                return new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        webSocketSessionService.addToUnclaimed(session.getId(), session);
                        super.afterConnectionEstablished(session);
                    }
                };
            }
        });
    }
}

/*
    At last, slightly change the preSend method of MyChannelInterceptor to call the method of the WebSocketSessionService
    that transfers a session from the unclaimed map to the sessions map.
*/

@Component
public class MyChannelInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {

        MessageHeaders headers = message.getHeaders();
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        StompCommand command = accessor != null ? accessor.getCommand() : null;
        String sessionId = accessor != null ? accessor.getSessionId() : null;

        if(command != null) {
            
            if (StompCommand.CONNECT.equals(command)) {
                
                String jwtToken = accessor.getFirstNativeHeader("Authorization");
                
                if(jwtToken == null) {
                    // It's best if you create a custom exception that extends Runtime, like MyNotAuthenticatedException extends RuntimeException
                    throw new RuntimeException("Not authenticated!");
                }

                String username = // myAuthService.getUsername(jwtToken);



                /* THIS IS THE ADDITION FOR THIS PART */

                webSocketSessionService.moveUnclaimedToSessions(sessionId, username);

                /* ---------------------------------- */



                // Set the user principal. From now on, subsequent calls to preSend will have access to this by calling accessor.getUser().getName()
                accessor.setUser(new Principal() {
                    @Override
                    public String getName() {
                        return username;
                    }
                });

            }
            else if(StompCommand.SUBSCRIBE.equals(command)) {
                
                MultiValueMap<String, String> multiValueMap = headers.get(StompHeaderAccessor.NATIVE_HEADERS, MultiValueMap.class);
                Principal principal = accessor.getUser(); // The user that was set in the previous CONNECT command
                String topic = multiValueMap.getFirst("destination"); // The topic to which the user wants to subscribe

                if(topic != null) {

                  if(/* user not allowed on topic: Custom logic that tokenizes the topic the decides if the user may subscribe. */) {
                    
                    // It's best if you create a custom exception that extends Runtime, like MyNotAuthorizedException extends RuntimeException
                    throw new RuntimeException("Not authorized to subscribe to " + topic);
                  }
                }
            }
            else if(StompCommand.SEND.equals(command)) {

              /* Write your logic here that will prevent users from sending messages to topics they are not allowed. */

            }
        }

        return message;
    }
}

/*
    Now, depending on your business logic, you have access to the WebSocketSession objects of each user and may
    terminate them according to your application's needs.
    e.g. You have detected suspicious activity and wish to punish the user. Put them in some blacklist which
    should be checked later on by your preSend implementation of MyChannelInterceptor and close all of their
    connections using the WebSocketSessionService service.
*/

/*
    If you want to inject code at the level of the http handshake before upgrading the connection to websocket, you may use this class.
    In a stateless environment, you cannot determine the identity of the user from the http handshake because SockJS does not allow sending
    http headers. However, you can inject a one-time-use token (which you would have acquired via a regular http route) to achieve authentication.
    If you do the following, you don't need to check for a jwt token in the Stomp headers in MyChannelInterceptor, because there will already
    be a UserPrincipal available.
*/

public class UserHandshakeHandler extends DefaultHandshakeHandler {
    private final Logger LOG = LoggerFactory.getLogger(UserHandshakeHandler.class);

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // Something like this could work
        MultiValueMap<String, String> queryParams = UriComponentsBuilder.fromUriString(request.getURI().toString()).build().getQueryParams();
        String jwt = queryParams.get("token").get(0);
        String username = //authService.getUsername(jwt);
        return new UserPrincipal(username);
    }
}
