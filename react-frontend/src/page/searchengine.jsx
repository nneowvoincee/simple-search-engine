import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Search, ExternalLink, Zap, Terminal, ChevronUp, ChevronDown,
  Radio, X, History, BookOpen, Filter, ArrowUpDown,
  ChevronLeft, ChevronRight, Hash, Calendar, FileText, BarChart2, Link2
} from 'lucide-react';

const API_BASE = 'http://localhost:8080/api';
const HISTORY_KEY = 'search_engine_history';

const loadHistory = () => {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
};

const saveHistory = (history) => {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
  } catch { /* 忽略容量错误 */ }
};

export default function SearchEngine() {
  const [query, setQuery] = useState('');
  const [allResults, setAllResults] = useState([]);
  const [lastQuery, setLastQuery] = useState('');
  const [phase, setPhase] = useState('idle');
  const [error, setError] = useState(null);

  const [filterText, setFilterText] = useState('');
  const [sortBy, setSortBy] = useState('score');
  const [currentPage, setCurrentPage] = useState(1);
  const itemsPerPage = 10;

  const [showKeywords, setShowKeywords] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [keywords, setKeywords] = useState([]);
  const [selectedKeywords, setSelectedKeywords] = useState([]);
  const [keywordSearch, setKeywordSearch] = useState('');
  const [history, setHistory] = useState(loadHistory());
  const [selectedHistory, setSelectedHistory] = useState([]);

  const [blink, setBlink] = useState(true);
  const [headerText, setHeaderText] = useState('');
  const fullHeader = 'COMP4321X::SEARCH_ENGINE';
  const inputRef = useRef(null);

  useEffect(() => {
    const t = setInterval(() => setBlink(b => !b), 530);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    let i = 0;
    const t = setInterval(() => {
      setHeaderText(fullHeader.slice(0, i + 1));
      i++;
      if (i >= fullHeader.length) clearInterval(t);
    }, 45);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    const fetchKeywords = async () => {
      try {
        const res = await fetch(`${API_BASE}/keywords`);
        if (!res.ok) throw new Error('Failed to fetch keywords');
        const data = await res.json();
        setKeywords(data.keywords || []);
      } catch (err) {
        console.warn('关键词列表加载失败，将从搜索结果累积', err);
      }
    };
    fetchKeywords();
  }, []);

  const doSearch = useCallback(async (q, silent = false) => {
    const trimmed = q.trim();
    if (!trimmed) return;

    if (!silent) {
      setPhase('loading');
      setError(null);
      setFilterText('');
      setCurrentPage(1);
    }

    try {
      const res = await fetch(`${API_BASE}/search?q=${encodeURIComponent(trimmed)}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setAllResults(data.results || []);
      setLastQuery(trimmed);
      setPhase('done');

      const entry = {
        query: trimmed,
        timestamp: Date.now(),
        count: data.count || data.results.length,
      };
      setHistory(prev => {
        const updated = [entry, ...prev.filter(h => h.query !== trimmed)].slice(0, 50);
        saveHistory(updated);
        return updated;
      });
    } catch (err) {
      setError(`搜索失败: ${err.message}`);
      setPhase('done');
    }
  }, []);

  const handleSearch = () => doSearch(query);

  const handleSimilar = (result) => {
    if (!result.topKeywords || result.topKeywords.length === 0) return;
    const top5 = result.topKeywords.slice(0, 5).map(k => k.term).join(' ');
    setQuery(top5);
    doSearch(top5);
  };

  const toggleKeyword = (kw) => {
    setSelectedKeywords(prev =>
      prev.includes(kw) ? prev.filter(k => k !== kw) : [...prev, kw]
    );
  };

  const submitKeywordSearch = () => {
    if (selectedKeywords.length === 0) return;
    const q = selectedKeywords.join(' ');
    setQuery(q);
    setSelectedKeywords([]);
    setShowKeywords(false);
    doSearch(q);
  };

  const filteredKeywords = useMemo(() => {
    if (!keywordSearch.trim()) return keywords;
    return keywords.filter(k => k.includes(keywordSearch.trim().toLowerCase()));
  }, [keywords, keywordSearch]);

  const replayHistory = (item) => {
    setQuery(item.query);
    setShowHistory(false);
    doSearch(item.query);
  };

  const toggleHistorySelection = (item) => {
    setSelectedHistory(prev => {
      const exists = prev.find(h => h.query === item.query);
      if (exists) return prev.filter(h => h.query !== item.query);
      if (prev.length >= 2) return prev;
      return [...prev, item];
    });
  };

  const mergeSelectedHistories = async () => {
    if (selectedHistory.length !== 2) return;
    const [q1, q2] = selectedHistory.map(h => h.query);
    setPhase('loading');
    try {
      const [res1, res2] = await Promise.all([
        fetch(`${API_BASE}/search?q=${encodeURIComponent(q1)}`),
        fetch(`${API_BASE}/search?q=${encodeURIComponent(q2)}`)
      ]);
      const [data1, data2] = await Promise.all([res1.json(), res2.json()]);
      const merged = [...data1.results];
      const ids = new Set(data1.results.map(r => r.pageId));
      for (const r of data2.results) {
        if (!ids.has(r.pageId)) {
          merged.push(r);
        }
      }
      merged.sort((a, b) => b.score - a.score);
      setAllResults(merged);
      setLastQuery(`Merge: "${q1}" + "${q2}"`);
      setPhase('done');
      setShowHistory(false);
      setSelectedHistory([]);
    } catch (err) {
      setError('合并搜索失败: ' + err.message);
      setPhase('done');
    }
  };

  const filteredResults = useMemo(() => {
    let results = allResults;
    if (filterText.trim()) {
      const lower = filterText.toLowerCase();
      results = results.filter(r =>
        r.title.toLowerCase().includes(lower) || r.url.toLowerCase().includes(lower)
      );
    }
    return results;
  }, [allResults, filterText]);

  const sortedResults = useMemo(() => {
    const copy = [...filteredResults];
    switch (sortBy) {
      case 'date':
        return copy.sort((a, b) => new Date(b.lastModified) - new Date(a.lastModified));
      case 'size':
        return copy.sort((a, b) => b.pageSize - a.pageSize);
      case 'score':
      default:
        return copy.sort((a, b) => b.score - a.score);
    }
  }, [filteredResults, sortBy]);

  const totalPages = Math.ceil(sortedResults.length / itemsPerPage);
  const pagedResults = useMemo(() => {
    const start = (currentPage - 1) * itemsPerPage;
    return sortedResults.slice(start, start + itemsPerPage);
  }, [sortedResults, currentPage, itemsPerPage]);

  useEffect(() => {
    setCurrentPage(1);
  }, [filterText, sortBy]);

  const keywordCount = keywords.length > 0 ? keywords.length : allResults.length > 0 ? '?' : '...';

  return (
    <div className="min-h-screen bg-black text-green-400 font-mono relative overflow-hidden">
      <div
        className="pointer-events-none fixed inset-0 z-50 opacity-10"
        style={{
          backgroundImage: 'repeating-linear-gradient(0deg, transparent, transparent 3px, rgba(0,0,0,1) 3px, rgba(0,0,0,1) 4px)',
        }}
      />
      <div className="pointer-events-none fixed top-0 left-0 w-full h-64 opacity-5"
        style={{ background: 'radial-gradient(ellipse at 50% 0%, #00ff41 0%, transparent 70%)' }} />

      <div className="max-w-4xl mx-auto px-4 py-8 relative z-10">
        {/* 头部 */}
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.4 }} className="mb-10">
          <div className="flex items-center gap-2 mb-3">
            <Radio size={12} className="text-green-500 animate-pulse" />
            <span className="text-green-600 text-xs tracking-widest uppercase">
              SYS // CONNECTED TO: {API_BASE}
            </span>
          </div>
          <div className="flex items-end gap-2">
            <h1 className="text-3xl font-bold text-green-200 tracking-tight leading-none">{headerText}</h1>
            <span className="text-green-300 text-2xl leading-none mb-px transition-opacity" style={{ opacity: blink ? 1 : 0 }}>▋</span>
          </div>
          <div className="flex items-center gap-4 mt-2 flex-wrap">
            <span className="text-green-600 text-xs tracking-widest">
              // v2.4.1 &nbsp;|&nbsp; INDEX TERMS: {keywordCount}
            </span>
            <button onClick={() => setShowKeywords(true)} className="text-green-500 hover:text-green-300 text-xs tracking-widest flex items-center gap-1">
              <BookOpen size={12} /> BROWSE INDEX
            </button>
            <button onClick={() => { setShowHistory(true); setSelectedHistory([]); }} className="text-green-500 hover:text-green-300 text-xs tracking-widest flex items-center gap-1">
              <History size={12} /> HISTORY
            </button>
          </div>
          <div className="mt-3 h-px bg-green-700" />
        </motion.div>

        {/* 搜索栏 */}
        <div className="mb-8">
          <div className="flex">
            <div className="flex-1 flex items-center border border-green-600 bg-zinc-950 px-3 py-3 focus-within:border-green-400 transition-colors duration-200"
              style={{ boxShadow: 'inset 0 0 12px rgba(0,255,65,0.05)' }}>
              <Terminal size={13} className="text-green-500 mr-2 flex-shrink-0" />
              <span className="text-green-400 mr-1 text-sm select-none">$&gt;&nbsp;</span>
              <input
                ref={inputRef}
                className="flex-1 bg-transparent text-green-200 outline-none placeholder-green-600 text-sm tracking-wide caret-green-300 min-w-0"
                placeholder="query..."
                value={query}
                onChange={e => setQuery(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleSearch()}
                autoFocus
              />
            </div>
            <motion.button
              whileTap={{ scale: 0.96 }}
              onClick={handleSearch}
              className="border border-l-0 border-green-600 px-5 py-3 text-green-400 hover:bg-green-800 hover:text-green-100 hover:border-green-400 transition-colors duration-200 text-xs tracking-widest flex items-center gap-2 flex-shrink-0"
            >
              <Search size={13} /> EXEC
            </motion.button>
          </div>
          <p className="text-green-600 text-xs mt-2 tracking-wide">// ENTER to execute &nbsp;·&nbsp; partial match supported</p>
        </div>

        {error && (
          <div className="mb-6 text-red-400 text-xs border border-red-600 p-3 bg-red-950/20">
            {error}
          </div>
        )}

        <AnimatePresence>
          {phase === 'loading' && (
            <motion.div key="loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              className="text-green-500 text-xs tracking-widest mb-6 animate-pulse">
              &gt;&gt; SCANNING INDEX... TRAVERSING GRAPH... PLEASE WAIT_
            </motion.div>
          )}
        </AnimatePresence>

        {phase === 'done' && (
          <motion.div initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} className="mb-6">
            <p className="text-xs tracking-wide mb-3">
              <span className="text-green-500">&gt;&nbsp;</span>
              <span className="text-green-400">Found&nbsp;</span>
              <span className="text-green-200 font-bold">{allResults.length}</span>
              <span className="text-green-400"> document(s) for query:&nbsp;</span>
              <span className="text-green-300">"{lastQuery}"</span>
            </p>
            <div className="flex flex-wrap items-center gap-4 text-xs">
              <div className="flex items-center gap-1.5">
                <ArrowUpDown size={12} className="text-green-500" />
                <button onClick={() => setSortBy('score')} className={`px-2 py-0.5 border ${sortBy === 'score' ? 'border-green-400 text-green-200' : 'border-green-600 text-green-500'} hover:border-green-400`}>Score</button>
                <button onClick={() => setSortBy('date')} className={`px-2 py-0.5 border ${sortBy === 'date' ? 'border-green-400 text-green-200' : 'border-green-600 text-green-500'} hover:border-green-400`}>Date</button>
                <button onClick={() => setSortBy('size')} className={`px-2 py-0.5 border ${sortBy === 'size' ? 'border-green-400 text-green-200' : 'border-green-600 text-green-500'} hover:border-green-400`}>Size</button>
              </div>
              <div className="flex items-center gap-1.5 ml-auto">
                <Filter size={12} className="text-green-500" />
                <input
                  className="bg-transparent border border-green-600 px-2 py-0.5 text-green-200 outline-none focus:border-green-400 w-40"
                  placeholder="filter results..."
                  value={filterText}
                  onChange={e => setFilterText(e.target.value)}
                />
              </div>
            </div>
            <div className="h-px bg-green-700 mt-3" />
          </motion.div>
        )}

        <AnimatePresence>
          {phase === 'done' && pagedResults.map((r, i) => (
            <motion.div
              key={r.pageId}
              initial={{ opacity: 0, x: -16 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: i * 0.04, duration: 0.25 }}
              className="mb-5 border border-green-700 bg-zinc-950 group hover:border-green-500 transition-colors duration-200"
            >
              <div className="flex items-center justify-between px-4 py-2 border-b border-green-700 group-hover:border-green-500 bg-black">
                <div className="flex items-center gap-2">
                  <span className="text-green-600 text-xs">▸</span>
                  <span className="text-green-500 text-xs tracking-widest">DOC_{String(r.pageId).padStart(4, '0')}</span>
                  <span className="text-green-600 text-xs ml-2 flex items-center gap-1"><BarChart2 size={10} /> {r.score.toFixed(2)}</span>
                </div>
                <span className="text-green-600 text-xs">{r.lastModified?.split(' ')[0]}</span>
              </div>

              <div className="p-4">
                <p className="text-green-200 font-bold text-sm tracking-wide mb-2 leading-snug">

                  <a href={r.url}>{r.title}</a>
                </p>
                <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mb-3 text-xs">
                  <div className="flex items-center gap-1">
                    <ExternalLink size={11} className="text-green-500" />
                    <a href={r.url} target="_blank" rel="noreferrer" className="text-green-400 hover:text-green-200 transition-colors underline decoration-green-600 hover:decoration-green-400 truncate max-w-md">{r.url}</a>
                  </div>
                  <span className="text-green-600 flex items-center gap-1"><Calendar size={10} /> {r.lastModified}</span>
                  <span className="text-green-600 flex items-center gap-1"><FileText size={10} /> {r.pageSize} B</span>
                  <span className="text-green-600 flex items-center gap-1"><Link2 size={10} /> {r.childLinks?.length || 0} links</span>
                </div>

                {r.topKeywords && r.topKeywords.length > 0 && (
                  <div className="flex flex-wrap gap-1 mb-3">
                    {r.topKeywords.slice(0, 5).map(k => (
                      <span key={k.term} className="text-green-600 text-xs border border-green-700 px-1.5 py-0.5">
                        {k.term}<span className="text-green-500 ml-1">({k.tf})</span>
                      </span>
                    ))}
                  </div>
                )}

                {r.childLinks && r.childLinks.length > 0 && (
                  <div className="border border-green-700 p-3 mb-3">
                    <div className="flex items-center gap-1.5 mb-2">
                      <ChevronDown size={11} className="text-green-500" />
                      <span className="text-green-500 text-xs tracking-widest uppercase">Outgoing Links</span>
                      <span className="text-green-600 text-xs">[{r.childLinks.length}]</span>
                    </div>
                    <div className="space-y-1 pl-2 border-l border-green-700 max-h-24 overflow-y-auto">
                      {r.childLinks.slice(0, 5).map((link, idx) => (
                        <a key={idx} href={link} target="_blank" rel="noreferrer" className="block text-green-600 text-xs hover:text-green-400 transition-colors truncate">{link}</a>
                      ))}
                      {r.childLinks.length > 5 && <span className="text-green-700 text-xs">... and {r.childLinks.length - 5} more</span>}
                    </div>
                  </div>
                )}

                <motion.button
                  whileHover={{ scale: 1.02 }}
                  whileTap={{ scale: 0.97 }}
                  onClick={() => handleSimilar(r)}
                  className="flex items-center gap-1.5 border border-green-600 px-3 py-1.5 text-green-500 text-xs tracking-widest hover:border-green-400 hover:text-green-300 hover:bg-green-800 transition-all duration-200"
                >
                  <Zap size={10} /> find similar pages
                </motion.button>
              </div>
            </motion.div>
          ))}
        </AnimatePresence>

        {phase === 'idle' && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 1 }} className="text-center py-16">
            <p className="text-green-600 text-xs tracking-widest">// AWAITING INPUT — TYPE A QUERY AND PRESS EXEC</p>
          </motion.div>
        )}

        {phase === 'done' && totalPages > 1 && (
          <div className="flex justify-center items-center gap-3 mt-8 text-xs">
            <button
              disabled={currentPage === 1}
              onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
              className="border border-green-600 px-2 py-1 text-green-500 hover:border-green-400 disabled:opacity-40"
            ><ChevronLeft size={12} /></button>
            <span className="text-green-400">Page {currentPage} / {totalPages}</span>
            <button
              disabled={currentPage === totalPages}
              onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
              className="border border-green-600 px-2 py-1 text-green-500 hover:border-green-400 disabled:opacity-40"
            ><ChevronRight size={12} /></button>
          </div>
        )}

        <div className="mt-14 border-t border-green-700 pt-4">
          <p className="text-green-600 text-xs tracking-widest">
            &gt; SEARCH_ENGINE // TERMS: {keywordCount} // © 2025
          </p>
        </div>
      </div>

      {/* 关键词索引模态 */}
      <AnimatePresence>
        {showKeywords && (
          <motion.div
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 bg-black/80 flex items-start justify-center pt-20"
            onClick={() => setShowKeywords(false)}
          >
            <motion.div
              initial={{ scale: 0.95 }} animate={{ scale: 1 }} exit={{ scale: 0.95 }}
              className="bg-zinc-950 border border-green-600 w-full max-w-lg max-h-[70vh] flex flex-col m-4"
              onClick={e => e.stopPropagation()}
            >
              <div className="flex items-center justify-between px-4 py-3 border-b border-green-600">
                <span className="text-green-200 text-sm tracking-wider flex items-center gap-2"><BookOpen size={14} /> Index Keywords</span>
                <button onClick={() => setShowKeywords(false)} className="text-green-400 hover:text-green-200"><X size={14} /></button>
              </div>
              <div className="p-3 border-b border-green-600">
                <input
                  className="w-full bg-black border border-green-600 px-3 py-1.5 text-green-200 text-xs outline-none focus:border-green-400"
                  placeholder="filter keywords..."
                  value={keywordSearch}
                  onChange={e => setKeywordSearch(e.target.value)}
                />
              </div>
              <div className="flex-1 overflow-y-auto p-2">
                <div className="flex flex-wrap gap-1">
                  {filteredKeywords.length === 0 ? (
                    <span className="text-green-500 text-xs p-2">NO KEYWORDS LOADED</span>
                  ) : (
                    filteredKeywords.map(kw => (
                      <button
                        key={kw}
                        onClick={() => toggleKeyword(kw)}
                        className={`text-xs px-2 py-1 border ${selectedKeywords.includes(kw) ? 'border-green-400 text-green-200 bg-green-800' : 'border-green-700 text-green-500 hover:border-green-500'}`}
                      >
                        {kw}
                      </button>
                    ))
                  )}
                </div>
              </div>
              <div className="p-3 border-t border-green-600 flex justify-between items-center">
                <span className="text-green-500 text-xs">{selectedKeywords.length} selected</span>
                <div className="flex gap-2">
                  <button onClick={() => setSelectedKeywords([])} className="border border-green-600 px-3 py-1 text-green-500 text-xs hover:text-green-300">Clear</button>
                  <button onClick={submitKeywordSearch} disabled={selectedKeywords.length === 0}
                    className="border border-green-500 px-3 py-1 text-green-300 text-xs hover:bg-green-800 disabled:opacity-30">
                    Search Selected
                  </button>
                </div>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* 查询历史面板 */}
      <AnimatePresence>
        {showHistory && (
          <motion.div
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 bg-black/80 flex items-start justify-center pt-20"
            onClick={() => { setShowHistory(false); setSelectedHistory([]); }}
          >
            <motion.div
              initial={{ scale: 0.95 }} animate={{ scale: 1 }} exit={{ scale: 0.95 }}
              className="bg-zinc-950 border border-green-600 w-full max-w-lg max-h-[70vh] flex flex-col m-4"
              onClick={e => e.stopPropagation()}
            >
              <div className="flex items-center justify-between px-4 py-3 border-b border-green-600">
                <span className="text-green-200 text-sm tracking-wider flex items-center gap-2"><History size={14} /> Query History</span>
                <button onClick={() => { setShowHistory(false); setSelectedHistory([]); }} className="text-green-400 hover:text-green-200"><X size={14} /></button>
              </div>
              <div className="flex-1 overflow-y-auto">
                {history.length === 0 ? (
                  <div className="p-6 text-green-500 text-xs text-center">NO HISTORY YET</div>
                ) : (
                  history.map((item, idx) => (
                    <div key={idx} className="flex items-center justify-between px-4 py-2 border-b border-green-700 hover:bg-green-800/30 transition-colors">
                      <button onClick={() => replayHistory(item)} className="text-left flex-1 mr-2">
                        <span className="text-green-200 text-xs">{item.query}</span>
                        <span className="text-green-500 text-xs ml-3">[{item.count} results]</span>
                        <span className="text-green-600 text-xs ml-3">{new Date(item.timestamp).toLocaleString()}</span>
                      </button>
                      <input
                        type="checkbox"
                        checked={!!selectedHistory.find(h => h.query === item.query)}
                        onChange={() => toggleHistorySelection(item)}
                        className="accent-green-500"
                      />
                    </div>
                  ))
                )}
              </div>
              {selectedHistory.length === 2 && (
                <div className="p-3 border-t border-green-600">
                  <button onClick={mergeSelectedHistories}
                    className="w-full border border-green-500 text-green-300 py-2 text-xs hover:bg-green-800 tracking-widest">
                    MERGE SELECTED QUERIES
                  </button>
                </div>
              )}
              <div className="p-2 border-t border-green-600 flex justify-between text-xs text-green-500">
                <span>Click item to replay</span>
                <span>Select 2 to merge</span>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}