/** @jsx React.DOM */

"use strict";

var React = require("react/addons");

  var TimeField = React.createClass({
    displayName: "TimeField",
    propTypes: {
      label: React.PropTypes.string.isRequired,
      time: React.PropTypes.oneOfType([
        React.PropTypes.string,
        React.PropTypes.object
      ])
    },
    render: function () {
      var time = this.props.time;

      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <div>
          <dt>{this.props.label}</dt>
          {
            time != null ?
              <dd>
                <time dateTime={time}>
                  {new Date(time).toLocaleString()}
                </time>
              </dd> :
              <dd className="text-muted">None</dd>
          }
        </div>
      );
    }
  });

module.exports = TimeField;
