import {presentMessageText} from '../vendor/cloud-runtime/src/protocol/message-references.ts';
// Message quotes with comments, byte-for-byte the Web wire format (`@lody/shared` message-quote.ts,
// the composer's `buildPastedTextRewrites` and `formatMessageQuoteExcerpt`). A quote is text so
// every agent receives the snapshot; a `message_quote` span (quoteVersion 1) marks it for readers.
// Android adaptation outside the vendored snapshot, whose transcript projection drops these spans.
export const MESSAGE_QUOTE_VERSION=1;
export const MAX_MESSAGE_QUOTE_CHARS=50000;
const HEADER='<message-quote>\nQuoted message (context):\n';
const FOOTER='\n</message-quote>';
// The header as it appears inside a JSON string, to skip envelopes without a quote unparsed.
const JSON_HEADER=JSON.stringify(HEADER).slice(1,-1);

export function formatMessageQuote(snapshot) {
 return HEADER+snapshot.replace(/\r\n?/g,'\n').split('\n').map(line=>`> ${line}`).join('\n')+FOOTER;
}

/** Only for an explicitly versioned quote span, never to guess quotes from user Markdown. */
export function readMessageQuote(text,version) {
 if(version!==MESSAGE_QUOTE_VERSION || typeof text!=='string' || !text.startsWith(HEADER) || !text.endsWith(FOOTER))return undefined;
 const lines=text.slice(HEADER.length,-FOOTER.length).split('\n');
 if(!lines.every(line=>line.startsWith('> ')))return undefined;
 return lines.map(line=>line.slice(2)).join('\n');
}

const segmenter=granularity=>typeof Intl?.Segmenter==='function' ? new Intl.Segmenter(undefined,{granularity}) : undefined;
const GRAPHEMES=segmenter('grapheme'), WORDS=segmenter('word');
const graphemes=text=>GRAPHEMES ? Array.from(GRAPHEMES.segment(text),({segment})=>segment) : Array.from(text);
const words=text=>WORDS ? Array.from(WORDS.segment(text),({segment})=>segment) : text.split(/(\s+)/).filter(Boolean);

/** The chip label's excerpt: whitespace collapsed, at most 32 graphemes, cut at a word boundary. */
export function messageQuoteExcerpt(snapshot,maxLength=32) {
 const text=snapshot.replace(/\s+/g,' ').trim();
 if(!text || graphemes(text).length<=maxLength)return text;
 const budget=Math.max(1,maxLength-3);
 let fitted='';
 for(const segment of words(text)) {
  const candidate=fitted+segment;
  if(graphemes(candidate.trimEnd()).length<=budget)fitted=candidate;else break;
 }
 const trimmed=fitted.trimEnd();
 if(trimmed)return `${trimmed}...`;
 return `${graphemes(text).slice(0,budget).join('').trimEnd()}...`;
}

/** Validates the native composer's quote cards: `{id, snapshot, comment}` in insertion order. */
export function quoteCards(value) {
 if(value===undefined || value===null)return [];
 if(!Array.isArray(value) || value.length>32)throw Error('invalid_send');
 return value.map(card=>{
  if(!card || typeof card!=='object' || typeof card.id!=='string' || !/^[a-zA-Z0-9_-]{1,128}$/.test(card.id) || typeof card.snapshot!=='string' || !card.snapshot.trim() || card.snapshot.length>MAX_MESSAGE_QUOTE_CHARS || typeof card.comment!=='string' || card.comment.length>32000)throw Error('invalid_send');
  return {id:card.id,snapshot:card.snapshot,comment:card.comment.trim()};
 });
}

/**
 * The final turn text after the main composer text (already rewritten for mentions): the main
 * text, then per card its quote block and, when it has one, its comment, joined by blank lines.
 * Main-text spans keep their offsets; each quote span sits at its block's final position.
 */
export function appendMessageQuotes(text,spans,cards) {
 let out=text;
 const result=[...(spans??[])];
 for(const card of cards) {
  if(out)out+='\n\n';
  const start=out.length;
  out+=formatMessageQuote(card.snapshot);
  result.push({start,end:out.length,kind:'message_quote',label:`[${messageQuoteExcerpt(card.snapshot)}]`,target:card.id,quoteVersion:MESSAGE_QUOTE_VERSION});
  if(card.comment)out+=`\n\n${card.comment}`;
 }
 return {text:out,spans:result};
}

/**
 * A user text item's transcript form when it carries valid quote spans: the text around each
 * quote is presented as upstream presents it (reference chips), each quote block stays verbatim,
 * and `quotes` records where it landed with its decoded snapshot. Null when there is none.
 */
export function presentQuotedText(text,spans) {
 if(typeof text!=='string' || !Array.isArray(spans) || !spans.length)return null;
 const ordered=spans.filter(s=>s && typeof s==='object' && Number.isInteger(s.start) && Number.isInteger(s.end) && s.start>=0 && s.end>s.start && s.end<=text.length)
  .sort((a,b)=>a.start-b.start || a.end-b.end);
 const kept=[];let lastEnd=0;
 for(const span of ordered){if(span.start<lastEnd)continue;kept.push(span);lastEnd=span.end;}
 const quotes=kept.filter(s=>s.kind==='message_quote').map(s=>({span:s,snapshot:readMessageQuote(text.slice(s.start,s.end),s.quoteVersion)})).filter(q=>q.snapshot!==undefined);
 if(!quotes.length)return null;
 let out='',cursor=0;
 const references=[],shown=[];
 const segment=(from,to)=>{
  const inside=kept.filter(s=>s.kind!=='message_quote' && s.start>=from && s.end<=to).map(s=>({...s,start:s.start-from,end:s.end-from}));
  const presented=presentMessageText(text.slice(from,to),inside);
  const base=out.length;
  out+=presented.text;
  for(const reference of presented.references)references.push({...reference,start:reference.start+base,end:reference.end+base});
 };
 for(const {span,snapshot} of quotes) {
  segment(cursor,span.start);
  const start=out.length;
  out+=text.slice(span.start,span.end);
  shown.push({start,end:out.length,snapshot});
  cursor=span.end;
 }
 segment(cursor,text.length);
 return {text:out,references,quotes:shown};
}

const rawItemsById=(sessionId,history,doc)=>{
 const byId=new Map();
 for(const entry of history(sessionId)??[])if(entry?.role==='user' && typeof entry.id==='string' && Array.isArray(entry.items))byId.set(entry.id,entry.items);
 let queued=[];
 try{queued=doc(sessionId)?.getMovableList('mq').toJSON()??[];}catch{queued=[];}
 for(const item of queued)if(typeof item?.userTurnId==='string' && !byId.has(item.userTurnId))byId.set(item.userTurnId,item.acpSessionConfig?.inputBlocks??[{type:'text',text:item.task}]);
 return byId;
};

/**
 * Adds quote blocks to a watched session's user turns. Only envelopes that contain a quote header
 * are parsed; each turn's presentation is cached by the text upstream projected, so a streaming
 * session reads its replica again only when a quoted turn changes.
 */
export function quoteSessionEnvelopes(history,doc) {
 const cache=new Map();
 return message=>{
  if(message?.type!=='session' || typeof message.session!=='string' || !message.session.includes(JSON_HEADER))return message;
  let envelope;
  try{envelope=JSON.parse(message.session);}catch{return message;}
  const sessionId=message.sessionId;
  const known=cache.get(sessionId)??new Map();
  let raw,changed=false;
  const next=new Map();
  for(const entry of Array.isArray(envelope.entries)?envelope.entries:[]) {
   if(entry?.role!=='user' || !Array.isArray(entry.items))continue;
   entry.items.forEach((item,index)=>{
    if(item?.type!=='text' || typeof item.text!=='string' || !item.text.includes(HEADER))return;
    const key=`${entry.id}\u0000${index}\u0000${item.text}`;
    let presented=known.get(key);
    if(presented===undefined) {
     raw??=rawItemsById(sessionId,history,doc);
     const source=raw.get(entry.id)?.[index];
     // A turn the replica cannot show yet stays plain and is read again on the next envelope.
     if(source===undefined)return;
     presented=source?.type==='text' ? presentQuotedText(source.text,source.spans) : null;
    }
    next.set(key,presented);
    if(!presented)return;
    entry.items[index]={...item,text:presented.text,references:presented.references,quotes:presented.quotes};
    changed=true;
   });
  }
  cache.set(sessionId,next);
  return changed ? {...message,session:JSON.stringify(envelope)} : message;
 };
}
