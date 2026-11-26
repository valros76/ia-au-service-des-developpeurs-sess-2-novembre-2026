var fs = require('fs');

function loadConfig(filePath, callback) {
  fs.exists(filePath, function(exists) {
    if (!exists) {
      callback(null, {});
      return;
    }
    fs.readFile(filePath, 'utf8', function(err, data) {
      if (err) {
        callback(err);
      } else {
        var parsed = JSON.parse(data);
        callback(null, parsed);
      }
    });
  });
}