package systems.dmx.topicmaps;

import systems.dmx.core.Association;
import systems.dmx.core.DMXObject;
import systems.dmx.core.RelatedAssociation;
import systems.dmx.core.RelatedTopic;
import systems.dmx.core.Topic;
import systems.dmx.core.model.AssociationModel;
import systems.dmx.core.model.ChildTopicsModel;
import systems.dmx.core.model.topicmaps.ViewAssoc;
import systems.dmx.core.model.topicmaps.ViewTopic;
import systems.dmx.core.model.topicmaps.ViewProps;
import systems.dmx.core.osgi.PluginActivator;
import systems.dmx.core.service.CoreService;
import systems.dmx.core.service.Transactional;
import systems.dmx.core.util.DMXUtils;
import systems.dmx.core.util.IdList;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.POST;
import javax.ws.rs.DELETE;
import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;



@Path("/topicmap")      // TODO: rename "/topicmaps"
@Consumes("application/json")
@Produces("application/json")
public class TopicmapsPlugin extends PluginActivator implements TopicmapsService, MessengerContext {

    // ------------------------------------------------------------------------------------------------------- Constants

    private static final String TOPICMAP_CONTEXT   = "dmx.topicmaps.topicmap_context";
    private static final String ROLE_TYPE_TOPICMAP = "dmx.core.default";
    private static final String ROLE_TYPE_CONTENT  = "dmx.topicmaps.topicmap_content";

    private static final String PROP_X          = "dmx.topicmaps.x";
    private static final String PROP_Y          = "dmx.topicmaps.y";
    private static final String PROP_VISIBILITY = "dmx.topicmaps.visibility";
    private static final String PROP_PINNED     = "dmx.topicmaps.pinned";

    // ---------------------------------------------------------------------------------------------- Instance Variables

    private Map<String, TopicmapType> topicmapTypes = new HashMap();
    private List<ViewmodelCustomizer> viewmodelCustomizers = new ArrayList();
    private Messenger me = new Messenger(this);

    @Context
    private HttpServletRequest request;

    private Logger logger = Logger.getLogger(getClass().getName());

    // -------------------------------------------------------------------------------------------------- Public Methods



    public TopicmapsPlugin() {
        // Note: registering the default topicmap type in the init() hook would be too late.
        // The topicmap type is already needed at install-in-DB time ### Still true? Use preInstall() hook?
        registerTopicmapType(new DefaultTopicmapType());
    }



    // ************************
    // *** TopicmapsService ***
    // ************************



    @POST
    @Transactional
    @Override
    public Topic createTopicmap(@QueryParam("name") String name,
                                @QueryParam("topicmap_type_uri") String topicmapTypeUri,
                                @QueryParam("private") boolean isPrivate) {
        logger.info("Creating topicmap \"" + name + "\" (topicmapTypeUri=\"" + topicmapTypeUri + "\", isPrivate=" +
            isPrivate +")");
        Topic topicmapTopic = dmx.createTopic(mf.newTopicModel("dmx.topicmaps.topicmap", mf.newChildTopicsModel()
            .put("dmx.topicmaps.topicmap_name", name)
            .put("dmx.topicmaps.topicmap_type_uri", topicmapTypeUri)
            .put("dmx.topicmaps.topicmap_state", getTopicmapType(topicmapTypeUri).initialTopicmapState(mf))
            .put("dmx.topicmaps.private", isPrivate)
        ));
        me.newTopicmap(topicmapTopic);      // FIXME: broadcast to eligible users only
        return topicmapTopic;
    }

    // ---

    @GET
    @Path("/{id}")
    @Override
    public Topicmap getTopicmap(@PathParam("id") long topicmapId, @QueryParam("include_childs") boolean includeChilds) {
        try {
            logger.info("Loading topicmap " + topicmapId + " (includeChilds=" + includeChilds + ")");
            // Note: a Topicmap is not a DMXObject. So the JerseyResponseFilter's automatic
            // child topic loading is not applied. We must load the child topics manually here.
            Topic topicmapTopic = dmx.getTopic(topicmapId).loadChildTopics();
            Map<Long, ViewTopic> topics = fetchTopics(topicmapTopic, includeChilds);
            Map<Long, ViewAssoc> assocs = fetchAssociations(topicmapTopic);
            //
            return new Topicmap(topicmapTopic.getModel(), topics, assocs);
        } catch (Exception e) {
            throw new RuntimeException("Fetching topicmap " + topicmapId + " failed", e);
        }
    }

    @Override
    public boolean isTopicInTopicmap(long topicmapId, long topicId) {
        return fetchTopicMapcontext(topicmapId, topicId) != null;
    }

    @Override
    public boolean isAssociationInTopicmap(long topicmapId, long assocId) {
        return fetchAssociationMapcontext(topicmapId, assocId) != null;
    }

    @GET
    @Path("/object/{id}")
    @Override
    public List<RelatedTopic> getTopicmapTopics(@PathParam("id") long objectId) {
        try {
            List<RelatedTopic> topicmapTopics = new ArrayList();
            DMXObject object = dmx.getObject(objectId);
            boolean isAssoc = object instanceof Association;
            for (RelatedTopic topic : object.getRelatedTopics((String) null, null, ROLE_TYPE_TOPICMAP,
                                                                                   "dmx.topicmaps.topicmap")) {
                if (isAssoc || visibility(topic.getRelatingAssociation())) {
                    topicmapTopics.add(topic);
                }
            }
            return topicmapTopics;
        } catch (Exception e) {
            throw new RuntimeException("Fetching topicmap topics of topic/assoc " + objectId + " failed", e);
        }
    }

    // ---

    @POST
    @Path("/{id}/topic/{topic_id}")
    @Transactional
    @Override
    public void addTopicToTopicmap(@PathParam("id") final long topicmapId,
                                   @PathParam("topic_id") final long topicId, final ViewProps viewProps) {
        try {
            // Note: a Mapcontext association must have no workspace assignment as it is not user-deletable
            dmx.getAccessControl().runWithoutWorkspaceAssignment(new Callable<Void>() {  // throws Exception
                @Override
                public Void call() {
                    if (isTopicInTopicmap(topicmapId, topicId)) {
                        throw new RuntimeException("Topic " + topicId + " already added to topicmap" + topicmapId);
                    }
                    createTopicMapcontext(topicmapId, topicId, viewProps);
                    return null;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Adding topic " + topicId + " to topicmap " + topicmapId + " failed " +
                "(viewProps=" + viewProps + ")", e);
        }
    }

    @Override
    public void addTopicToTopicmap(long topicmapId, long topicId, int x, int y, boolean visibility) {
        addTopicToTopicmap(topicmapId, topicId, mf.newViewProps(x, y, visibility, false));   // pinned=false
    }

    @POST
    @Path("/{id}/association/{assoc_id}")
    @Transactional
    @Override
    public void addAssociationToTopicmap(@PathParam("id") final long topicmapId,
                                         @PathParam("assoc_id") final long assocId, final ViewProps viewProps) {
        try {
            // Note: a Mapcontext association must have no workspace assignment as it is not user-deletable
            dmx.getAccessControl().runWithoutWorkspaceAssignment(new Callable<Void>() {  // throws Exception
                @Override
                public Void call() {
                    if (isAssociationInTopicmap(topicmapId, assocId)) {
                        throw new RuntimeException("Association " + assocId + " already added to topicmap " +
                            topicmapId);
                    }
                    createAssociationMapcontext(topicmapId, assocId, viewProps);
                    return null;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Adding association " + assocId + " to topicmap " + topicmapId + " failed " +
                "(viewProps=" + viewProps + ")", e);
        }
    }

    @POST
    @Path("/{id}/topic/{topic_id}/association/{assoc_id}")
    @Transactional
    @Override
    public void addRelatedTopicToTopicmap(@PathParam("id") final long topicmapId,
                                          @PathParam("topic_id") final long topicId,
                                          @PathParam("assoc_id") final long assocId, final ViewProps viewProps) {
        try {
            // Note: a Mapcontext association must have no workspace assignment as it is not user-deletable
            dmx.getAccessControl().runWithoutWorkspaceAssignment(new Callable<Void>() {  // throws Exception
                @Override
                public Void call() {
                    // 1) add topic
                    Association topicMapcontext = fetchTopicMapcontext(topicmapId, topicId);
                    if (topicMapcontext == null) {
                        createTopicMapcontext(topicmapId, topicId, viewProps);
                    } else {
                        if (!visibility(topicMapcontext)) {
                            setTopicVisibility(topicmapId, topicId, true);      // TODO: don't refetch mapcontext
                        }
                    }
                    // 2) add association
                    Association assocMapcontext = fetchAssociationMapcontext(topicmapId, assocId);
                    if (assocMapcontext == null) {
                        createAssociationMapcontext(topicmapId, assocId, mf.newViewProps()
                            .put(PROP_VISIBILITY, false)
                            .put(PROP_PINNED, false)
                        );
                    } else {
                        if (!visibility(assocMapcontext)) {
                            setAssocVisibility(topicmapId, assocId, true);      // TODO: don't refetch mapcontext
                        }
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Adding related topic " + topicId + " (assocId=" + assocId + ") to topicmap " +
                topicmapId + " failed (viewProps=" + viewProps + ")", e);
        }
    }

    // ---

    @PUT
    @Path("/{id}/topic/{topic_id}")
    @Transactional
    @Override
    public void setTopicViewProps(@PathParam("id") long topicmapId, @PathParam("topic_id") long topicId,
                                                                    ViewProps viewProps) {
        storeTopicViewProps(topicmapId, topicId, viewProps);
    }

    @PUT
    @Path("/{id}/association/{assoc_id}")
    @Transactional
    @Override
    public void setAssociationViewProps(@PathParam("id") long topicmapId, @PathParam("assoc_id") long assocId,
                                                                          ViewProps viewProps) {
        storeAssociationViewProps(topicmapId, assocId, viewProps);
    }

    @PUT
    @Path("/{id}/topic/{topic_id}/{x}/{y}")         // TODO: extend URL .../x/{x}/y/{y}
    @Transactional
    @Override
    public void setTopicPosition(@PathParam("id") long topicmapId, @PathParam("topic_id") long topicId,
                                                                   @PathParam("x") int x, @PathParam("y") int y) {
        try {
            storeTopicViewProps(topicmapId, topicId, mf.newViewProps(x, y));
            me.setTopicPosition(topicmapId, topicId, x, y);
        } catch (Exception e) {
            throw new RuntimeException("Setting position of topic " + topicId + " in topicmap " + topicmapId +
                " failed", e);
        }
    }

    @PUT
    @Path("/{id}/topic/{topic_id}/{visibility}")    // TODO: extend URL .../visibility/{visibility}
    @Transactional
    @Override
    public void setTopicVisibility(@PathParam("id") long topicmapId, @PathParam("topic_id") long topicId,
                                                                     @PathParam("visibility") boolean visibility) {
        try {
            if (!visibility) {
                // hide topic's associations
                hideAssocsWithPlayer(dmx.getTopic(topicId), topicmapId);
            }
            // show/hide topic
            storeTopicViewProps(topicmapId, topicId, mf.newViewProps(visibility));
            // send message
            me.setTopicVisibility(topicmapId, topicId, visibility);
        } catch (Exception e) {
            throw new RuntimeException("Setting visibility of topic " + topicId + " in topicmap " + topicmapId +
                " failed", e);
        }
    }

    @PUT
    @Path("/{id}/assoc/{assoc_id}/visibility/{visibility}")
    @Transactional
    @Override
    public void setAssocVisibility(@PathParam("id") long topicmapId, @PathParam("assoc_id") long assocId,
                                                                     @PathParam("visibility") boolean visibility) {
        try {
            if (!visibility) {
                // hide assoc's associations
                hideAssocsWithPlayer(dmx.getAssociation(assocId), topicmapId);
            }
            // show/hide topic ### FIXME: idempotence of remove-assoc-from-topicmap is needed for delete-muti
            storeAssociationViewProps(topicmapId, assocId, mf.newViewProps(visibility));
            // send message
            me.setAssocVisibility(topicmapId, assocId, visibility);
        } catch (Exception e) {
            throw new RuntimeException("Setting visibility of assoc " + assocId + " from topicmap " + topicmapId +
                " failed", e);
        }
    }

    // ---

    @PUT
    @Path("/{id}/topics/{topicIds}/visibility/false")
    @Transactional
    @Override
    public void hideTopics(@PathParam("id") long topicmapId, @PathParam("topicIds") IdList topicIds) {
        hideMulti(topicmapId, topicIds, new IdList());
    }

    @PUT
    @Path("/{id}/assocs/{assocIds}/visibility/false")
    @Transactional
    @Override
    public void hideAssocs(@PathParam("id") long topicmapId, @PathParam("assocIds") IdList assocIds) {
        hideMulti(topicmapId, new IdList(), assocIds);
    }

    @PUT
    @Path("/{id}/topics/{topicIds}/assocs/{assocIds}/visibility/false")
    @Transactional
    @Override
    public void hideMulti(@PathParam("id") long topicmapId, @PathParam("topicIds") IdList topicIds,
                                                            @PathParam("assocIds") IdList assocIds) {
        logger.info("topicmapId=" + topicmapId + ", topicIds=" + topicIds + ", assocIds=" + assocIds);
        for (long id : topicIds) {
            setTopicVisibility(topicmapId, id, false);
        }
        for (long id : assocIds) {
            setAssocVisibility(topicmapId, id, false);
        }
    }

    // ---

    @PUT
    @Path("/{id}")
    @Transactional
    @Override
    public void setClusterPosition(@PathParam("id") long topicmapId, ClusterCoords coords) {
        for (ClusterCoords.Entry entry : coords) {
            setTopicPosition(topicmapId, entry.topicId, entry.x, entry.y);
        }
    }

    @PUT
    @Path("/{id}/translation/{x}/{y}")
    @Transactional
    @Override
    public void setTopicmapTranslation(@PathParam("id") long topicmapId, @PathParam("x") int transX,
                                                                         @PathParam("y") int transY) {
        try {
            ChildTopicsModel topicmapState = mf.newChildTopicsModel()
                .put("dmx.topicmaps.topicmap_state", mf.newChildTopicsModel()
                    .put("dmx.topicmaps.translation", mf.newChildTopicsModel()
                        .put("dmx.topicmaps.translation_x", transX)
                        .put("dmx.topicmaps.translation_y", transY)));
            dmx.updateTopic(mf.newTopicModel(topicmapId, topicmapState));
        } catch (Exception e) {
            throw new RuntimeException("Setting translation of topicmap " + topicmapId + " failed (transX=" +
                transX + ", transY=" + transY + ")", e);
        }
    }

    // ---

    @Override
    public void registerTopicmapType(TopicmapType topicmapType) {
        logger.info("### Registering topicmap type \"" + topicmapType.getClass().getName() + "\"");
        topicmapTypes.put(topicmapType.getUri(), topicmapType);
    }

    // ---

    @Override
    public void registerViewmodelCustomizer(ViewmodelCustomizer customizer) {
        logger.info("### Registering viewmodel customizer \"" + customizer.getClass().getName() + "\"");
        viewmodelCustomizers.add(customizer);
    }

    @Override
    public void unregisterViewmodelCustomizer(ViewmodelCustomizer customizer) {
        logger.info("### Unregistering viewmodel customizer \"" + customizer.getClass().getName() + "\"");
        if (!viewmodelCustomizers.remove(customizer)) {
            throw new RuntimeException("Unregistering viewmodel customizer failed (customizer=" + customizer + ")");
        }
    }

    // ---

    // Note: not part of topicmaps service
    @GET
    @Path("/{id}")
    @Produces("text/html")
    public InputStream getTopicmapInWebclient() {
        // Note: the path parameter is evaluated at client-side
        return invokeWebclient();
    }

    // Note: not part of topicmaps service
    @GET
    @Path("/{id}/topic/{topic_id}")
    @Produces("text/html")
    public InputStream getTopicmapAndTopicInWebclient() {
        // Note: the path parameters are evaluated at client-side
        return invokeWebclient();
    }



    // ************************
    // *** MessengerContext ***
    // ************************



    @Override
    public CoreService getCoreService() {
        return dmx;
    }

    @Override
    public HttpServletRequest getRequest() {
        return request;
    }



    // ------------------------------------------------------------------------------------------------- Private Methods

    // --- Fetch Topicmap ---

    private Map<Long, ViewTopic> fetchTopics(Topic topicmapTopic, boolean includeChilds) {
        Map<Long, ViewTopic> topics = new HashMap();
        List<RelatedTopic> relTopics = topicmapTopic.getRelatedTopics(TOPICMAP_CONTEXT,
            ROLE_TYPE_TOPICMAP, ROLE_TYPE_CONTENT, null);       // othersTopicTypeUri=null
        if (includeChilds) {
            DMXUtils.loadChildTopics(relTopics);
        }
        for (RelatedTopic topic : relTopics) {
            topics.put(topic.getId(), buildViewTopic(topic));
        }
        return topics;
    }

    private Map<Long, ViewAssoc> fetchAssociations(Topic topicmapTopic) {
        Map<Long, ViewAssoc> assocs = new HashMap();
        List<RelatedAssociation> relAssocs = topicmapTopic.getRelatedAssociations(TOPICMAP_CONTEXT,
            ROLE_TYPE_TOPICMAP, ROLE_TYPE_CONTENT, null);       // othersAsspcTypeUri=null
        for (RelatedAssociation assoc : relAssocs) {
            assocs.put(assoc.getId(), buildViewAssoc(assoc));
        }
        return assocs;
    }

    // ---

    private ViewTopic buildViewTopic(RelatedTopic topic) {
        try {
            ViewProps viewProps = fetchTopicViewProps(topic.getRelatingAssociation());
            invokeViewmodelCustomizers(topic, viewProps);
            return mf.newViewTopic(topic.getModel(), viewProps);
        } catch (Exception e) {
            throw new RuntimeException("Creating viewmodel for topic " + topic.getId() + " failed", e);
        }
    }

    private ViewAssoc buildViewAssoc(RelatedAssociation assoc) {
        try {
            ViewProps viewProps = fetchAssocViewProps(assoc.getRelatingAssociation());
            // invokeViewmodelCustomizers(assoc, viewProps);    // TODO: assoc customizers?
            return mf.newViewAssoc(assoc.getModel(), viewProps);
        } catch (Exception e) {
            throw new RuntimeException("Creating viewmodel for association " + assoc.getId() + " failed", e);
        }
    }

    // --- Fetch View Properties ---

    private ViewProps fetchTopicViewProps(Association topicmapContext) {
        return mf.newViewProps(
            (Integer) topicmapContext.getProperty(PROP_X),
            (Integer) topicmapContext.getProperty(PROP_Y),
            visibility(topicmapContext),
            pinned(topicmapContext)
        );
    }

    private ViewProps fetchAssocViewProps(Association topicmapContext) {
        return mf.newViewProps()
            .put(PROP_VISIBILITY, visibility(topicmapContext))
            .put(PROP_PINNED, pinned(topicmapContext));
    }

    private boolean visibility(Association topicmapContext) {
        return (Boolean) topicmapContext.getProperty(PROP_VISIBILITY);
    }

    private boolean pinned(Association topicmapContext) {
        return (Boolean) topicmapContext.getProperty(PROP_PINNED);
    }

    // --- Store View Properties ---

    /**
     * Convenience.
     */
    private void storeTopicViewProps(long topicmapId, long topicId, ViewProps viewProps) {
        try {
            Association topicmapContext = fetchTopicMapcontext(topicmapId, topicId);
            if (topicmapContext == null) {
                throw new RuntimeException("Topic " + topicId + " is not contained in topicmap " + topicmapId);
            }
            storeViewProps(topicmapContext, viewProps);
        } catch (Exception e) {
            throw new RuntimeException("Storing view properties of topic " + topicId + " failed " +
                "(viewProps=" + viewProps + ")", e);
        }
    }

    /**
     * Convenience.
     */
    private void storeAssociationViewProps(long topicmapId, long assocId, ViewProps viewProps) {
        try {
            Association topicmapContext = fetchAssociationMapcontext(topicmapId, assocId);
            if (topicmapContext == null) {
                throw new RuntimeException("Association " + assocId + " is not contained in topicmap " + topicmapId);
            }
            storeViewProps(topicmapContext, viewProps);
        } catch (Exception e) {
            throw new RuntimeException("Storing view properties of association " + assocId + " failed " +
                "(viewProps=" + viewProps + ")", e);
        }
    }

    private void storeViewProps(Association topicmapContext, ViewProps viewProps) {
        for (String propUri : viewProps) {
            topicmapContext.setProperty(propUri, viewProps.get(propUri), false);    // addToIndex = false
        }
    }

    // --- Topicmap Contexts ---

    private Association fetchTopicMapcontext(long topicmapId, long topicId) {
        return dmx.getAssociation(TOPICMAP_CONTEXT, topicmapId, topicId,
            ROLE_TYPE_TOPICMAP, ROLE_TYPE_CONTENT);
    }

    private Association fetchAssociationMapcontext(long topicmapId, long assocId) {
        return dmx.getAssociationBetweenTopicAndAssociation(TOPICMAP_CONTEXT, topicmapId, assocId,
            ROLE_TYPE_TOPICMAP, ROLE_TYPE_CONTENT);
    }

    // ---

    private void createTopicMapcontext(long topicmapId, long topicId, ViewProps viewProps) {
        Association topicMapcontext = dmx.createAssociation(mf.newAssociationModel(TOPICMAP_CONTEXT,
            mf.newTopicRoleModel(topicmapId, ROLE_TYPE_TOPICMAP),
            mf.newTopicRoleModel(topicId,    ROLE_TYPE_CONTENT)
        ));
        storeViewProps(topicMapcontext, viewProps);
        //
        ViewTopic topic = mf.newViewTopic(dmx.getTopic(topicId).getModel(), viewProps);
        me.addTopicToTopicmap(topicmapId, topic);
    }

    private void createAssociationMapcontext(long topicmapId, long assocId, ViewProps viewProps) {
        Association assocMapcontext = dmx.createAssociation(mf.newAssociationModel(TOPICMAP_CONTEXT,
            mf.newTopicRoleModel(topicmapId,    ROLE_TYPE_TOPICMAP),
            mf.newAssociationRoleModel(assocId, ROLE_TYPE_CONTENT)
        ));
        storeViewProps(assocMapcontext, viewProps);
        //
        ViewAssoc assoc = mf.newViewAssoc(dmx.getAssociation(assocId).getModel(), viewProps);
        me.addAssociationToTopicmap(topicmapId, assoc);
    }

    // ---

    private void hideAssocsWithPlayer(DMXObject player, long topicmapId) {
        for (Association assoc : player.getAssociations()) {
            Association topicmapContext = fetchAssociationMapcontext(topicmapId, assoc.getId());
            if (topicmapContext != null) {
                storeViewProps(topicmapContext, mf.newViewProps(false));
                hideAssocsWithPlayer(assoc, topicmapId);     // recursion
            }
        }
    }

    // TODO: drop it (not in use)
    private void deleteAssociationMapcontext(Association assocMapcontext) {
        // Note: a mapcontext association has no workspace assignment -- it belongs to the system.
        // Deleting a mapcontext association is a privileged operation.
        dmx.getAccessControl().deleteAssociationMapcontext(assocMapcontext);
    }

    // --- Viewmodel Customizers ---

    private void invokeViewmodelCustomizers(RelatedTopic topic, ViewProps viewProps) {
        for (ViewmodelCustomizer customizer : viewmodelCustomizers) {
            invokeViewmodelCustomizer(customizer, topic, viewProps);
        }
    }

    private void invokeViewmodelCustomizer(ViewmodelCustomizer customizer, RelatedTopic topic,
                                                                           ViewProps viewProps) {
        try {
            customizer.enrichViewProps(topic, viewProps);
        } catch (Exception e) {
            throw new RuntimeException("Invoking viewmodel customizer for topic " + topic.getId() + " failed " +
                "(customizer=\"" + customizer.getClass().getName() + "\")", e);
        }
    }

    // --- Topicmap Types ---

    private TopicmapType getTopicmapType(String topicmapTypeUri) {
        TopicmapType topicmapType = topicmapTypes.get(topicmapTypeUri);
        if (topicmapType == null) {
            throw new RuntimeException("Topicmap type \"" + topicmapTypeUri + "\" not registered");
        }
        return topicmapType;
    }

    // ---

    private InputStream invokeWebclient() {
        return dmx.getPlugin("systems.dmx.webclient").getStaticResource("/web/index.html");
    }
}
