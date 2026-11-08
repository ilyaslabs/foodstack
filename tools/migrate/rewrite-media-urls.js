// Rewrites stored image addresses after the photos moved (for example from one server's storage to
// an S3 bucket or a CDN): every string that starts with OLD_BASE is given NEW_BASE instead, in
// every collection of every foodstack_* database. Brands, dishes and past orders keep a copy of
// their image addresses, so this is needed once after the move (handbook runbooks 40 and 49).
//
//   OLD_BASE=https://admin.example.com/foodstack-public NEW_BASE=https://cdn.example.com \
//     mongosh "<connection string>" --quiet --file rewrite-media-urls.js
//
// DRY_RUN=true counts the documents that would change without writing. Each document is replaced by
// its _id, which a sharded cluster accepts from MongoDB 7.1.
const oldBase = (process.env.OLD_BASE || '').replace(/\/+$/, '');
const newBase = (process.env.NEW_BASE || '').replace(/\/+$/, '');
const dryRun = process.env.DRY_RUN === 'true';
if (!/^https?:\/\/[^/]+/.test(oldBase) || !/^https?:\/\/[^/]+/.test(newBase)) {
  throw new Error('Set OLD_BASE and NEW_BASE to the old and new image addresses (https://host/path)');
}

// A sub-document: not a BSON value (ObjectId, Long, Decimal128, Binary carry _bsontype), a date or a pattern.
function isDocument(value) {
  return value !== null && typeof value === 'object' && value._bsontype === undefined
    && Object.prototype.toString.call(value) === '[object Object]';
}

function rewrite(value) {
  if (typeof value === 'string') {
    return value === oldBase || value.startsWith(oldBase + '/') ? newBase + value.slice(oldBase.length) : value;
  }
  if (Array.isArray(value)) return value.map(rewrite);
  if (isDocument(value)) {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = rewrite(v);
    return out;
  }
  return value; // ObjectId, Date, Decimal128, Binary and the like stay as they are
}

let total = 0;
for (const { name } of db.adminCommand({ listDatabases: 1, nameOnly: true }).databases) {
  if (!name.startsWith('foodstack_')) continue;
  const database = db.getSiblingDB(name);
  for (const info of database.getCollectionInfos({ type: 'collection' })) {
    const collection = database.getCollection(info.name);
    let changed = 0;
    const cursor = collection.find({});
    while (cursor.hasNext()) {
      const doc = cursor.next();
      const updated = rewrite(doc);
      if (JSON.stringify(EJSON.serialize(updated)) === JSON.stringify(EJSON.serialize(doc))) continue;
      changed++;
      if (!dryRun) collection.replaceOne({ _id: doc._id }, updated);
    }
    if (changed) print(`${name}.${info.name}: ${changed} ${dryRun ? 'would change' : 'changed'}`);
    total += changed;
  }
}
print(`${total} document(s) ${dryRun ? 'would change' : 'changed'}`);
