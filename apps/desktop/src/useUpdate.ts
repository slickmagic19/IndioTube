// The update state, as the screens see it.
//
// One shared controller holds the answer to "is there a newer IndioTube?",
// and every screen that shows an update button reads it from here.
import { useEffect, useState } from 'react';
import { updater, type UpdateState } from './updater';

export function useUpdateState(): UpdateState {
  const [state, setState] = useState<UpdateState>(() => updater.getState());
  useEffect(() => updater.subscribe(setState), []);
  return state;
}
