
ab = -> { a: 'aa', b: 'bb' }
a  = -> { a: 'aa' }
b  = -> { b: 'bb' }

a2b2 = -> { a: 'aa2', b: 'bb2' }
a2  = -> { a: 'aa2' }
b2  = -> { b: 'bb2' }

a2b = -> { a: 'aa2', b: 'bb' }


describe '_.kick', ->
  it 'should kick away properties from objects', ->
    expect(_(ab()).kick('b')).toEqual(a())
    expect(_(ab()).kick('a')).toEqual(b())
    expect(_(ab()).kick('c')).toEqual(ab())


testMapValsHelper = (f) ->
  expect(f({}, (value) -> value + '2')).toEqual {}
  expect(f(a(), (value) -> value + '2')).toEqual a2()
  expect(f(ab(), (value) -> value + '2')).toEqual a2b2()
  onlyA = f ab(), (value, key) ->
    if key is 'a' then value + '2' else value
  expect(onlyA).toEqual a2b()


describe '_.mapVals', ->
  it 'should transform object property values', ->
    testMapValsHelper(_.mapVals)


describe '_.mapValsKickUndef', ->
  it 'work as mapVals, and also remove keys mapping to `undefined`', ->
    testMapValsHelper(_.mapValsKickUndef)
    expect(_.mapValsKickUndef({}, -> undefined)).toEqual {}
    expect(_.mapValsKickUndef(a(), -> undefined)).toEqual {}
    expect(_.mapValsKickUndef(ab(), -> undefined)).toEqual {}
    # Kick 'b':
    onlyA = _.mapValsKickUndef ab(), (value, key) ->
      if key is 'a' then value + '2' else undefined
    expect(onlyA).toEqual a2()


# vim: fdm=marker et ts=2 sw=2 fo=tcqwn list tw=80