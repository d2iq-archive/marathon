/** @jsx React.DOM */

var React = require("react/addons");

module.exports = React.createClass({
    propTypes: {
      router: React.PropTypes.object.isRequired
    },

    onClick: function() {
      this.props.router.navigate("#apps" + this.props.model.get("id"), {trigger: true});
    },

    render: function() {
      var model = this.props.model;

      var isDeploying = model.isDeploying();

      var runningTasksClassSet = React.addons.classSet({
        "text-warning": !model.allInstancesBooted()
      });

      var statusClassSet = React.addons.classSet({
        "text-warning": isDeploying
      });

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        // Set `title` on cells that potentially overflow so hovering on the
        // cells will reveal their full contents.
        <tr onClick={this.onClick}>
          <td className="overflow-ellipsis" title={model.get("id")}>
            {model.get("id")}
          </td>
          <td className="text-right">{model.get("mem")}</td>
          <td className="text-right">{model.get("cpus")}</td>
          <td className="text-right">
            <span className={runningTasksClassSet}>
              {model.formatTasksRunning()}
            </span> / {model.get("instances")}
          </td>
          <td className="text-right">
            <span className={statusClassSet}>
              {isDeploying ? "Deploying" : "Running" }
            </span>
          </td>
        </tr>
      );
    }
  });
