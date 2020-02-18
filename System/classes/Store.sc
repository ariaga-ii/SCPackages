Store : LibraryBase {
	classvar global;
	classvar lastId = 1000;
	classvar lookups;
	
	*getId {
		lastId = lastId + 1;
		^lastId;
	}

	*global { ^global }
	*global_ { arg obj; global = obj; }

	*initClass {
		global = this.new;
		global.put('timingContext', (type: 'timingContext', bpm: 60));
		global.put('type', 'store');
		lookups = Dictionary();
	}

	*getPath { arg id;
		^lookups[id];
	}

	*setPath { arg id, path;
		lookups[id] = path;
	}

	*archive { arg path;
		global.writeMinifiedTextArchive(path);
	}

	*readFromArchive { arg path;
		var maxArchiveId = 0;
		global = path.load;
		global.treeDo({ |path, object, argument|
			var id = path[ path.size -1 ];
			if (id.class == Integer) {
				maxArchiveId = max(maxArchiveId, id);
				this.setPath(id, path);
			};
		});	
		lastId = maxArchiveId;

	}

	*getItems { arg id;
		var store = id !? { this.at(id) } ?? { global.dictionary };
		^store.select({ | value, key | key.class == Integer });
	}

	*getSortedItems { arg id;
		var items = this.getItems(id);
		^items.values.sort({ |a, b| a.timestamp < b.timestamp });
	}

	*addTimingContext { arg timingContext, id;
		var store = id !? { this.at(id) } ?? { global };
		store.put('timingContext', timingContext);
	}

	*getTimingContext { arg objectId;
		var path = this.getPath(objectId);
		var parentPath = path[ .. path.size - 2 ];
		while ({ parentPath.size > 0 }, {
			var parent = super.at(parentPath);
			parent['timingContext'] !? { arg timingContext;
				^timingContext
			};
			parentPath = parentPath[ .. parentPath.size - 2];
		});
		^global['timingContext'];
	}

	*at { arg id;
		var path = this.getPath(id);
		path ?? { ^nil };
		^super.at(*path);
	}	

	*addObject { arg object, parentId;
		var id = this.getId();
		var lookupPath = parentId !? {
			this.getPath(parentId) ?? { ^nil };
		} ?? [];

		object.id = id;
		lookupPath = lookupPath.add(id);
		this.setPath(id, lookupPath);
		
		this.put(*(lookupPath ++ [object]));
		^id
	}

	*removeObject { arg id;
		var path = this.getPath(id);
		this.put(*(path ++ [nil]));
		this.setPath(id, nil)
	}

	*updateObject { arg id, newState;
		var object = this.at(id);
		var prevState = object.copy;
		var diff = getDiff(object, newState);

		if (diff.size > 0) {
			object.putAll(diff);
		};

		^object;
	}

	*patch { arg patch;
		var historyPatch = MultiLevelIdentityDictionary();
		patch.treeDo({ |path, object, argument|
			if (object != global.at(path), {
				historyPatch.put(path, object);
				global.put(path, object);
				Dispatcher((type: 'objectUpdated', payload: object));
			});
		});
	}

	*getBase {
		var base = (type: 'store', timestamp: 0);
		^base
			.putAll(global.dictionary)
			.select({ |value, key|
				key.class == Integer
			});
	}
}

StoreHistory {
	classvar <history, <future;
	classvar <enabled;
	/**
	 * StoreHistory is made up of 2 stacks
	 * history - list of HistoryMarkers
 	 * future  - list of HistoryMarkers
 	 *
 	 * when some change is made to objects in the store
 	 * (and we want to treat these changes as one 'action') 
 	 * eg objects with ids 1001, 1002 we create a HistoryMarker
 	 * out of the state of the objects before the change
 	 *
 	 * a HistoryMarker is an object (dictionary) with shape eg.
 	 * 1001 -> (
 	 *	changedKey1: previousValue1,
 	 *  changedKey2: previousValue2,
 	 * 	changedKey3: previousValue3,
 	 * ),
 	 * 1002 -> (
 	 *	changedKey1: previousValue1,
 	 *  changedKey2: previousValue2,
 	 * 	changedKey3: previousValue3,
 	 * )
	 *
 	 * And then this HistoryMarker is pushed to the history stack 
	 * when we want to restore the store's state to that historymarker
	 * we pop that HistoryMarker from the history stack, prepend it to the future stack
	 * and go through each id in the history marker, and for that object set the changedKey back
	 * to the previousValue
	 *
	 **/

	*initClass {
		history = List();
		future = List();
		enabled = false;
	}

	*enable {
		enabled = true;

		Dispatcher.addListener('undo', {
			this.restoreFromHistory;
		});

		Dispatcher.addListener('redo', {
			this.restoreFromFuture;
		});
	}

	*disable {
		enabled = false;
		// Dispatcher.removeListener('')
	}

	*store { arg marker;
		if (enabled) {
			history.add(marker);
			future = List();
		}
	}

	*restoreFromHistory {
		this.restore(history) !? { |marker|
			future.add(marker);
		}
	}

	*restoreFromFuture {
		this.restore(future) !? { |marker|
			history.add(marker)
		}
	}

	*restore { arg list;
		if (list.size > 0 && enabled) {
			var state = list.removeAt(list.size - 1);
			var id = state.id;
			var object = Store.at(id);
			var marker = state.collect { |val, key|
				object[key]
			};

			Store.updateObject(id, state, false);
			^marker
		} {
			^nil
		}
	}

	*clearHistory {
		history = List();
		future = List();
	}
}

Dispatcher {

	classvar listeners;

	*initClass {
		listeners = Dictionary();
	}

	*addListener { arg type, listener;
		listeners.at(type) !? _.add(listener) ?? {
			listeners.put(type, Set[listener])
		}
	}

	*removeListener { arg type, listener;
		listeners.at(type) !? _.remove(listener);
	}

	*new { arg editEvent;
		var type = editEvent.type;
		var typeListeners = listeners.at(type) ?? [];

		typeListeners.do { arg listener;
			listener.value(editEvent.payload)
		};
	}
}