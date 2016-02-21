package de.deepamehta.core.impl;

import de.deepamehta.core.model.AssociationModel;
import de.deepamehta.core.model.AssociationTypeModel;
import de.deepamehta.core.model.DeepaMehtaObjectModel;
import de.deepamehta.core.model.RelatedTopicModel;
import de.deepamehta.core.model.RoleModel;
import de.deepamehta.core.service.DeepaMehtaEvent;
import de.deepamehta.core.service.Directive;
import de.deepamehta.core.service.ResultList;

import org.codehaus.jettison.json.JSONObject;

import java.util.List;



/**
 * Collection of the data that makes up an {@link Association}.
 *
 * @author <a href="mailto:jri@deepamehta.de">Jörg Richter</a>
 */
class AssociationModelImpl extends DeepaMehtaObjectModelImpl implements AssociationModel {

    // ---------------------------------------------------------------------------------------------- Instance Variables

    private RoleModel roleModel1;   // may be null in models used for an update operation
    private RoleModel roleModel2;   // may be null in models used for an update operation

    // ---------------------------------------------------------------------------------------------------- Constructors

    AssociationModelImpl(DeepaMehtaObjectModel object, RoleModel roleModel1, RoleModel roleModel2) {
        super(object);
        this.roleModel1 = roleModel1;
        this.roleModel2 = roleModel2;
    }

    AssociationModelImpl(AssociationModel assoc) {
        super(assoc);
        this.roleModel1 = assoc.getRoleModel1();
        this.roleModel2 = assoc.getRoleModel2();
    }

    // -------------------------------------------------------------------------------------------------- Public Methods

    @Override
    public RoleModel getRoleModel1() {
        return roleModel1;
    }

    @Override
    public RoleModel getRoleModel2() {
        return roleModel2;
    }

    // ---

    @Override
    public void setRoleModel1(RoleModel roleModel1) {
        this.roleModel1 = roleModel1;
    }

    @Override
    public void setRoleModel2(RoleModel roleModel2) {
        this.roleModel2 = roleModel2;
    }

    // --- Convenience Methods ---

    /**
     * @teturn  this association's role that matches the given role type.
     *          If no role matches, null is returned.
     *          <p>
     *          If both roles are matching an exception is thrown.
     */
    @Override
    public RoleModel getRoleModel(String roleTypeUri) {
        boolean rm1 = roleModel1.getRoleTypeUri().equals(roleTypeUri);
        boolean rm2 = roleModel2.getRoleTypeUri().equals(roleTypeUri);
        if (rm1 && rm2) {
            throw new RuntimeException("Ambiguous getRoleModel() call: both players occupy role \"" +
                roleTypeUri + "\" in association (" + this + ")");
        } else if (rm1) {
            return roleModel1;
        } else if (rm2) {
            return roleModel2;
        }
        return null;
    }

    @Override
    public long getOtherPlayerId(long id) {
        long id1 = roleModel1.getPlayerId();
        long id2 = roleModel2.getPlayerId();
        if (id1 == id) {
            return id2;
        } else if (id2 == id) {
            return id1;
        } else {
            throw new IllegalArgumentException("ID " + id + " doesn't refer to a player in " + this);
        }
    }

    @Override
    public boolean hasSameRoleTypeUris() {
        return roleModel1.getRoleTypeUri().equals(roleModel2.getRoleTypeUri());
    }



    // === Implementation of the abstract methods ===

    @Override
    public RoleModel createRoleModel(String roleTypeUri) {
        return mf.newAssociationRoleModel(id, roleTypeUri);
    }



    // === Serialization ===

    @Override
    public JSONObject toJSON() {
        try {
            return super.toJSON()
                .put("role_1", roleModel1.toJSON())
                .put("role_2", roleModel2.toJSON());
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed (" + this + ")", e);
        }
    }



    // === Java API ===

    @Override
    public AssociationModel clone() {
        try {
            AssociationModel model = (AssociationModel) super.clone();
            model.setRoleModel1(roleModel1.clone());
            model.setRoleModel2(roleModel2.clone());
            return model;
        } catch (Exception e) {
            throw new RuntimeException("Cloning an AssociationModel failed", e);
        }
    }

    @Override
    public String toString() {
        return "association (" + super.toString() + ", " + roleModel1 + ", " + roleModel2 + ")";
    }



    // ----------------------------------------------------------------------------------------- Package Private Methods

    @Override
    String className() {
        return "association";
    }

    // ---

    @Override
    AssociationTypeModel getType() {
        return pl.typeStorage.getAssociationType(typeUri);
    }

    @Override
    List<AssociationModel> getAssociations() {
        return pl.fetchAssociationAssociations(id);
    }

    @Override
    ResultList<RelatedTopicModel> getRelatedTopics(String assocTypeUri, String myRoleTypeUri,
                                                   String othersRoleTypeUri, String othersTopicTypeUri) {
        return pl.fetchAssociationRelatedTopics(id, assocTypeUri, myRoleTypeUri, othersRoleTypeUri,
            othersTopicTypeUri);
    }

    @Override
    ResultList<RelatedTopicModel> getRelatedTopics(List assocTypeUris, String myRoleTypeUri,
                                                   String othersRoleTypeUri, String othersTopicTypeUri) {
        return pl.fetchAssociationRelatedTopics(id, assocTypeUris, myRoleTypeUri, othersRoleTypeUri,
            othersTopicTypeUri);
    }

    @Override
    void storeUri() {
        pl.storeAssociationUri(id, uri);
    }

    @Override
    void delete() {
        pl.deleteAssociation(id);
    }

    // ---

    @Override
    DeepaMehtaEvent getPreDeleteEvent() {
        return CoreEvent.PRE_DELETE_ASSOCIATION;
    }

    @Override
    DeepaMehtaEvent getPostDeleteEvent() {
        return CoreEvent.POST_DELETE_ASSOCIATION;
    }

    // ---

    @Override
    Directive getDeleteDirective() {
        return Directive.DELETE_ASSOCIATION;
    }
}
