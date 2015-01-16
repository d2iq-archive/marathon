/** @jsx React.DOM */


var React = require("react/addons");
var States = require("../constants/States");
var AppComponent = require("../components/AppComponent");
var BackboneMixin = require("../mixins/BackboneMixin");

module.exports = React.createClass({
    displayName: "AppListComponent",

    mixins: [BackboneMixin],

    propTypes: {
      collection: React.PropTypes.object.isRequired,
      router: React.PropTypes.object.isRequired
    },

    getResource: function() {
      return this.props.collection;
    },

    sortCollectionBy: function(comparator) {
      var collection = this.props.collection;
      comparator =
        collection.sortKey === comparator && !collection.sortReverse ?
        "-" + comparator :
        comparator;
      collection.setComparator(comparator);
      collection.sort();
    },

    render: function() {
      var sortKey = this.props.collection.sortKey;

      var appNodes;
      var tableClassName = "table table-fixed";

      var headerClassSet = React.addons.classSet({
        "clickable": true,
        "dropup": this.props.collection.sortReverse
      });

      if (this.props.fetchState === States.STATE_LOADING) {
        appNodes =
          <tr>
            <td className="text-center text-muted" colSpan="5">
              Loading apps...
            </td>
          </tr>;
      } else if (this.props.collection.length === 0) {
        appNodes =
          <tr>
            <td className="text-center" colSpan="5">No running apps.</td>
          </tr>;
      } else {

        /* jshint trailing:false, quotmark:false, newcap:false */
        appNodes = this.props.collection.map(function(model) {
          return <AppComponent
                    key={model.id}
                    model={model}
                    router={this.props.router} />;
        }, this);

        // Give rows the selectable look when there are apps to click.
        tableClassName += " table-hover table-selectable";
      }

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <table className={tableClassName}>
          <colgroup>
            <col style={{width: "28%"}} />
            <col style={{width: "18%"}} />
            <col style={{width: "18%"}} />
            <col style={{width: "18%"}} />
            <col style={{width: "18%"}} />
          </colgroup>
          <thead>
            <tr>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "id")} className={headerClassSet}>
                  ID {sortKey === "id" ? <span className="caret"></span> : null}
                </span>
              </th>
              <th className="text-right">
                <span onClick={this.sortCollectionBy.bind(null, "mem")} className={headerClassSet}>
                  {sortKey === "mem" ? <span className="caret"></span> : null} Memory (MB)
                </span>
              </th>
              <th className="text-right">
                <span onClick={this.sortCollectionBy.bind(null, "cpus")} className={headerClassSet}>
                  {sortKey === "cpus" ? <span className="caret"></span> : null} CPUs
                </span>
              </th>
              <th className="text-right">
                <span onClick={this.sortCollectionBy.bind(null, "instances")} className={headerClassSet}>
                  {sortKey === "instances" ? <span className="caret"></span> : null} Tasks / Instances
                </span>
              </th>
              <th className="text-right">
                <span onClick={this.sortCollectionBy.bind(null, "isDeploying")} className={headerClassSet}>
                  {sortKey === "isDeploying" ? <span className="caret"></span> : null} Status
                </span>
              </th>
            </tr>
          </thead>
          <tbody>
            {
              (this.props.fetchState === States.STATE_ERROR) ?
                <tr>
                  <td className="text-center text-danger" colSpan="5">
                    Error fetching apps. Refresh to try again.
                  </td>
                </tr> :
                null
            }
            {appNodes}
          </tbody>
        </table>
      );
    }
  });
