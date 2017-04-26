import http from 'axios'
import Utils from './utils'
import { Topic, Assoc, TopicType, AssocType } from './model'

export default {

  // Note: exceptions thrown in the handlers passed to then/catch are swallowed silently!
  // They do not reach the caller. Apparently they're swallowed by the ES6 Promises
  // implementation, not by axios. See Matt Zabriskie's (the author of axios) comment here:
  // https://github.com/mzabriskie/axios/issues/42
  // See also:
  // http://jamesknelson.com/are-es6-promises-swallowing-your-errors/
  //
  // As a workaround we catch here explicitly and log the error at least.
  // Note: the caller continues to work with flawed (undefined) data then!

  getTopic (id) {
    return http.get('/core/topic/' + id).then(response =>
      new Topic(response.data)
    ).catch(error => {
      console.error(error)
    })
  },

  getTopicsByType (typeUri) {
    return http.get('/core/topic/by_type/' + typeUri).then(response =>
      Utils.instantiate(response.data, Topic)
    ).catch(error => {
      console.error(error)
    })
  },

  getAssoc (id) {
    return http.get('/core/association/' + id).then(response =>
      new Assoc(response.data)
    ).catch(error => {
      console.error(error)
    })
  },

  getAllTopicTypes () {
    return http.get('/core/topictype/all').then(response =>
      Utils.instantiate(response.data, TopicType)
    ).catch(error => {
      console.error(error)
    })
  },

  getAllAssocTypes () {
    return http.get('/core/assoctype/all').then(response =>
      Utils.instantiate(response.data, AssocType)
    ).catch(error => {
      console.error(error)
    })
  },

  getTopicmap (id) {
    return http.get('/topicmap/' + id).then(response =>
      response.data
    ).catch(error => {
      console.error(error)
    })
  }
}
