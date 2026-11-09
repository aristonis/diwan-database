/* Version switcher: reads <api root>/versions.json and shows a bar at the top of every page.
   The build sits at <api root>/<target>/; without versions.json (or without http) no bar is added. */
(function () {
  'use strict';
  var path = window.location.pathname;
  var at = path.lastIndexOf('/api/');
  if (at < 0 || !/^https?:$/.test(window.location.protocol)) { return; }
  var apiRoot = path.slice(0, at + 5);
  var rest = path.slice(at + 5).split('/');
  var current = rest.shift();
  var page = rest.join('/');
  var SEMVER = /^\d+\.\d+\.\d+$/;

  function parse(v) { return v.split('.').map(Number); }
  function compare(a, b) {
    var x = parse(a), y = parse(b);
    for (var i = 0; i < 3; i++) { if (x[i] !== y[i]) { return y[i] - x[i]; } }
    return 0;
  }
  function option(value, label, selected) {
    var o = document.createElement('option');
    o.value = value; o.textContent = label; o.selected = selected;
    return o;
  }
  function select(label, options, onChange) {
    var s = document.createElement('select');
    s.setAttribute('aria-label', label);
    options.forEach(function (o) { s.appendChild(o); });
    s.addEventListener('change', function () { onChange(s.value); });
    return s;
  }
  function go(target) {
    var url = apiRoot + target + '/' + page;
    var fallback = apiRoot + target + '/index.html';
    fetch(url, { method: 'HEAD' }).then(function (r) {
      window.location.href = r.ok ? url : fallback;
    }).catch(function () { window.location.href = fallback; });
  }

  function render(data) {
    var releases = (data.releases || []).filter(function (v) { return SEMVER.test(v); }).sort(compare);
    if (!releases.length && !data.dev) { return; }
    var cur = SEMVER.test(current) ? parse(current) : null;
    var bar = document.createElement('div');
    bar.id = 'diwan-version-bar';
    bar.appendChild(document.createTextNode('Version'));

    var inMajor = function (m) { return releases.filter(function (v) { return parse(v)[0] === m; }); };
    var minorsOf = function (m) {
      var out = [];
      inMajor(m).forEach(function (v) { var n = parse(v)[1]; if (out.indexOf(n) < 0) { out.push(n); } });
      return out;
    };
    var patchesOf = function (m, n) { return inMajor(m).filter(function (v) { return parse(v)[1] === n; }); };
    var newestOfMinor = function (m, n) { return patchesOf(m, n)[0]; };

    var majors = [];
    releases.forEach(function (v) { var m = parse(v)[0]; if (majors.indexOf(m) < 0) { majors.push(m); } });
    var major = cur ? cur[0] : 0;
    var opts = majors.map(function (m) { return option('M' + m, m + '.x', cur !== null && cur[0] === m); });
    if (data.dev) { opts.push(option('dev', 'dev', current === 'dev')); }
    bar.appendChild(select('Major version', opts, function (val) {
      if (val === 'dev') { go('dev'); return; }
      var m = parseInt(val.slice(1), 10);
      go(newestOfMinor(m, minorsOf(m)[0]));
    }));

    if (cur) {
      bar.appendChild(select('Minor version', minorsOf(major).map(function (n) {
        return option('m' + n, major + '.' + n, n === cur[1]);
      }), function (val) { go(newestOfMinor(major, parseInt(val.slice(1), 10))); }));
      var patches = patchesOf(major, cur[1]);
      if (patches.length > 1) {
        bar.appendChild(select('Patch version', patches.map(function (v) {
          return option(v, v, v === current);
        }), go));
      }
    }
    document.body.insertBefore(bar, document.body.firstChild);
  }

  fetch(apiRoot + 'versions.json').then(function (r) { return r.ok ? r.json() : null; })
    .then(function (d) { if (d) { render(d); } })
    .catch(function () { /* no switcher without versions.json */ });
})();
