const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/com/project/lol/service/MediaSearch.kt', 'utf8');
const template = source.match(/return "(if\(typeof window\.searchMediaItems.*)"/)[1];

async function run(query, results) {
    let captured, played, injected = false;
    const context = {
        window: {searchMediaItems: async q => {captured = q; return results;}},
        playFromUri: id => {played = id;},
        actStop: () => {injected = true;},
    };
    vm.runInNewContext(template.replace('$quoted', JSON.stringify(query)), context);
    await new Promise(resolve => setImmediate(resolve));
    return {captured, played, injected};
}

test('voice query is data, including quotes and JavaScript delimiters', async () => {
    const query = "'); actStop(); //\n\"\\";
    const result = await run(query, [{id: 'spotify:track:123', browsable: false}]);
    assert.equal(result.captured, query);
    assert.equal(result.injected, false);
    assert.equal(result.played, 'spotify:track:123');
});

test('voice playback skips browsable results and tolerates missing results', async () => {
    assert.equal((await run('artist', [{id: 'artist', browsable: true}, {id: 'track', browsable: false}])).played, 'track');
    assert.equal((await run('missing', [])).played, undefined);
    assert.equal((await run('missing', undefined)).played, undefined);
});
