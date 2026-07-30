import React, { useState, useEffect, useMemo, useCallback } from "react";
import { useSearchParams } from "react-router-dom";
import axios from "axios";
import { API_BASE_URL } from "@/config";
import {
  Typography,
  Card,
  CardHeader,
  CardBody,
  Button,
  Input,
  Textarea,
  Spinner,
  Chip,
  Select,
  Option,
  Dialog,
  DialogHeader,
  DialogBody,
  DialogFooter,
  Popover,
  PopoverHandler,
  PopoverContent,
  Alert,
  Progress,
  IconButton,
  Tooltip,
} from "@material-tailwind/react";
import { useAuth } from "@/context/AuthContext";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { useTheme } from "@/context/ThemeContext.jsx";
import { usePaywall } from "@/context/PaywallContext.jsx";
import {
  InformationCircleIcon,
  SparklesIcon,
  FireIcon,
  CheckCircleIcon,
  BookOpenIcon,
  ChevronRightIcon,
  XMarkIcon
} from "@heroicons/react/24/solid";
import toast from "react-hot-toast";
import RecommendationCard from "../../components/RecommendationCard";
import AICoachCard from "../../components/AICoachCard";

/* =========================
   Constants & Helpers
========================= */

const BASE_URL = API_BASE_URL.replace(/\/api$/, "");
const FREE_ACTION_LIMIT = 10;

const STATUS_STYLES = {
  CORRECT: {
    dot: "bg-green-500 shadow-green-500/50",
    text: "text-green-500",
    label: "Correct!",
    chip: "border-green-200 bg-green-50/50 text-green-700 dark:border-green-900/50 dark:bg-green-900/20 dark:text-green-300"
  },
  CLOSE: {
    dot: "bg-orange-500 shadow-orange-500/50",
    text: "text-orange-500",
    label: "Close!",
    chip: "border-orange-200 bg-orange-50/50 text-orange-800 dark:border-orange-900/50 dark:bg-orange-900/20 dark:text-orange-300"
  },
  REVEALED: {
    dot: "bg-blue-500 shadow-blue-500/50",
    text: "text-blue-500",
    label: "Solution Revealed",
    chip: "border-blue-200 bg-blue-50/50 text-blue-700 dark:border-blue-900/50 dark:bg-blue-900/20 dark:text-blue-300"
  },
  INCORRECT: {
    dot: "bg-red-500 shadow-red-500/50",
    text: "text-red-500",
    label: "Incorrect",
    chip: "border-red-200 bg-red-50/50 text-red-700 dark:border-red-900/50 dark:bg-red-900/20 dark:text-red-300"
  }
};

function DynamicFeedbackTitle({ status }) {
  const style = STATUS_STYLES[status] || STATUS_STYLES.INCORRECT;
  return (
    <div className="flex items-center gap-2 mb-2 animate-fade-in-scale">
      <div className={`w-3 h-3 rounded-full shadow-[0_0_8px] ${style.dot} transition-all duration-500`}></div>
      <Typography variant="h6" className={`font-bold tracking-tight uppercase text-sm ${style.text}`}>
        {style.label}
      </Typography>
    </div>
  );
}

const formatMarkdownText = (text) => text ? text.replace(/\\n/g, '\n') : "";

const getStatusChip = (status) => {
  const baseClasses = "py-0.5 px-2.5 text-[11px] font-bold uppercase tracking-wide w-fit rounded-full border transition-all duration-300 hover:scale-105";
  if (!status) return <Chip variant="ghost" className={`${baseClasses} border-blue-gray-100 text-blue-gray-500`} value="N/A" />;
  const style = STATUS_STYLES[status?.toUpperCase()] || STATUS_STYLES.INCORRECT;
  return <Chip variant="ghost" className={`${baseClasses} ${style.chip}`} value={status === "REVEALED" ? "Revealed" : style.label} />;
};

// Circular Progress Gauge
const CircularGauge = ({ value, loading }) => {
  const radius = 17;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (value / 100) * circumference;

  let color = "text-red-500";
  if (value > 70) color = "text-green-500";
  else if (value > 40) color = "text-yellow-500";

  if (loading) return <Spinner className="h-5 w-5" />;

  return (
    <div className={`relative inline-flex h-12 w-12 items-center justify-center shrink-0 ${color}`}>
      <svg className="h-12 w-12 transform -rotate-90 overflow-visible">
        <circle className="text-gray-200 dark:text-gray-700" strokeWidth="4" stroke="currentColor" fill="transparent" r={radius} cx="24" cy="24" />
        <circle
          className={`${color} transition-all duration-1000 ease-out`}
          strokeWidth="4"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          strokeLinecap="round"
          stroke="currentColor"
          fill="transparent"
          r={radius}
          cx="24"
          cy="24"
        />
        <text
          x="24"
          y="24"
          textAnchor="middle"
          dominantBaseline="middle"
          className="fill-current text-[10px] font-bold tabular-nums"
          transform="rotate(90 24 24)"
        >
          {Math.round(value)}%
        </text>
      </svg>
    </div>
  );
};

/* =========================
   Component
========================= */

export function Practice() {
  const { user, decrementFreeActions } = useAuth();
  const { theme } = useTheme();
  const { showPaywall } = usePaywall();

  const [error, setError] = useState(null);
  const [isPolling, setIsPolling] = useState(false);

  // History State
  const [allHistory, setAllHistory] = useState([]);
  const [itemsToShow, setItemsToShow] = useState(10);
  const [loadingHistory, setLoadingHistory] = useState(true);
  const [searchTerm, setSearchTerm] = useState("");
  const [selectedHistory, setSelectedHistory] = useState(null);

  // Deep Linking
  const [searchParams] = useSearchParams();

  useEffect(() => {
    const pSubject = searchParams.get("subject");
    const pTopic = searchParams.get("topic");
    const pDifficulty = searchParams.get("difficulty");

    if (pSubject) setSubject(pSubject);
    if (pTopic) setTopic(pTopic);
    if (pDifficulty) setDifficulty(pDifficulty);
  }, [searchParams]);

  // Generator State
  const [subject, setSubject] = useState("Java");
  const [topic, setTopic] = useState("Object Oriented Programming");
  const [difficulty, setDifficulty] = useState("Beginner");
  const [generating, setGenerating] = useState(false);

  // Question/Answer State
  const [question, setQuestion] = useState(null);
  const [currentAnswer, setCurrentAnswer] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState(null);
  const [textareaRows, setTextareaRows] = useState(5);
  const [hint, setHint] = useState(null);
  const [loadingHint, setLoadingHint] = useState(false);
  const [loadingAnswer, setLoadingAnswer] = useState(false);
  const [openPopover, setOpenPopover] = useState(false);
  const [streak, setStreak] = useState(0);


  // Fusion Features
  const [prediction, setPrediction] = useState(null);
  const [loadingPrediction, setLoadingPrediction] = useState(false);
  const [recommendations, setRecommendations] = useState(null);
  const [activeContext, setActiveContext] = useState(null);

  useEffect(() => {
    const fetchData = async () => {
      const token = localStorage.getItem("token");
      if (!token) return;
      const config = { headers: { "Authorization": `Bearer ${token}` } };

      try {
        // Recommendations
        const recRes = await axios.get(`${BASE_URL}/api/recommendations/dashboard`, config);
        setRecommendations(recRes.data);

        // Active Study Plan Context
        const ctxRes = await axios.get(`${BASE_URL}/api/study-plans/active-context`, config);
        if (ctxRes.data && ctxRes.data.planId) {
          setActiveContext(ctxRes.data);
        }
      } catch (err) {
        console.error("Failed to load dashboard data", err);
      }
    };
    fetchData();
  }, []);

  useEffect(() => {
    const fetchPrediction = async () => {
      if (!subject || !topic || !difficulty) return;
      setLoadingPrediction(true);
      try {
        const token = localStorage.getItem("token");
        if (!token) return;
        const res = await axios.get(`${BASE_URL}/api/recommendations/predict`, {
          params: { topic, difficulty },
          headers: { "Authorization": `Bearer ${token}` }
        });
        setPrediction(res.data);
      } catch (err) {
        console.error("Failed to get prediction", err);
      } finally {
        setLoadingPrediction(false);
      }
    };

    const debounce = setTimeout(fetchPrediction, 800);
    return () => clearTimeout(debounce);
  }, [subject, topic, difficulty]);

  const actionsRemaining = Math.max(0, FREE_ACTION_LIMIT - (user?.freeActionsUsed || 0));

  // --- API Helpers ---
  const getAuthHeaders = useCallback(() => {
    const token = localStorage.getItem("token");
    return { headers: { "Authorization": `Bearer ${token}` } };
  }, []);

  const fetchHistory = useCallback(async () => {
    setLoadingHistory(true);
    setError(null);
    try {
      const res = await axios.get(`${BASE_URL}/api/practice/history`, getAuthHeaders());
      const raw = Array.isArray(res?.data?.history) ? res.data.history : [];
      const sorted = [...raw].sort((a, b) => new Date(b.submittedAt || b.generatedAt || 0) - new Date(a.submittedAt || a.generatedAt || 0));
      setAllHistory(sorted);
    } catch (err) {
      console.error("Error fetching history:", err);
    } finally {
      setLoadingHistory(false);
    }
  }, [getAuthHeaders]);

  useEffect(() => {
    if (user) fetchHistory();
  }, [user, fetchHistory]);

  const pollForResult = async (qId) => {
    for (let i = 0; i < 10; i++) {
      try {
        await new Promise(resolve => setTimeout(resolve, 3000));
        const res = await axios.get(`${BASE_URL}/api/practice/history`, getAuthHeaders());
        const found = res.data.history.find(item => item.questionId === qId);
        if (found && found.evaluationStatus) return found;
      } catch (err) {
        console.warn("Polling check attempt failed...", err);
      }
    }
    return null;
  };

  // --- Handlers ---
  const handleAnswerChange = (e) => {
    const value = e.target.value;
    setCurrentAnswer(value);
    const rows = (value.match(/\n/g) || []).length + 1;
    setTextareaRows(Math.min(Math.max(5, rows), 15));
  };

  const handleGenerateQuestion = async () => {
    const token = localStorage.getItem("token");
    if (!token) {
      setError("You must be logged in to generate questions.");
      return;
    }

    let context = {};
    if (question && feedback && feedback.evaluationStatus) {
      context = {
        previousQuestionId: question.id,
        previousStatus: feedback.evaluationStatus
      };
    }

    setGenerating(true);
    setError("");
    setFeedback(null);
    setHint(null);
    setCurrentAnswer("");
    setQuestion(null);

    try {
      const response = await axios.post(`${BASE_URL}/api/ai/generate-question`, {
        subject,
        topic,
        difficulty,
        ...context
      }, getAuthHeaders());
      setQuestion(response.data);
      await fetchHistory();
    } catch (err) {
      console.error("Error generating question:", err);
      setError(err.response?.data?.message || err.response?.data?.error || "Failed to generate question. Please try again.");
    } finally {
      setGenerating(false);
    }
  };

  const handleSubmissionLogic = async (apiCall) => {
    setSubmitting(true);
    setFeedback(null);
    setHint(null);
    setError(null);
    setIsPolling(true);

    try {
      const config = { ...getAuthHeaders(), timeout: 90000 };
      const res = await apiCall(config);
      processFeedback(res.data);

      await fetchHistory();
      setIsPolling(false);
      if (user?.subscriptionStatus === "FREE") decrementFreeActions();
    } catch (err) {
      if (err.response?.status === 402) {
        showPaywall();
        setIsPolling(false);
        return;
      }
      console.log("Direct response failed, switching to polling...", err);
      const foundItem = await pollForResult(question.id);
      setIsPolling(false);
      if (foundItem) {
        processFeedback({
          evaluationStatus: foundItem.evaluationStatus,
          answerText: foundItem.answerText,
          feedback: foundItem.feedback,
          hint: foundItem.hint
        });
        await fetchHistory();
        if (user?.subscriptionStatus === "FREE") decrementFreeActions();
      } else {
        setError("We couldn't verify your result due to a connection timeout. Please check your history shortly.");
        toast.error("Verification timeout");
      }
    } finally {
      setSubmitting(false);
      setLoadingAnswer(false);
    }
  };

  const processFeedback = (data) => {
    setFeedback(data);

    if (data.evaluationStatus === "CORRECT") {
      toast.success("Correct! +10 XP", { icon: "🎉" });
      setStreak(s => s + 1);

      // Pattern Match for plan update tag [PLAN_UPDATE:X]
      const match = data.feedback?.match(/\[PLAN_UPDATE:(\d+)\]/);
      if (match) {
        const count = match[1];
        toast.success(`Study Plan updated! ${count} items marked complete.`, {
          icon: "📖",
          duration: 5000
        });
        // Refresh context to show progress
        axios.get(`${BASE_URL}/api/study-plans/active-context`, getAuthHeaders())
          .then(res => setActiveContext(res.data));
      }
    } else {
      setStreak(0);
      if (data.evaluationStatus === "CLOSE") {
        toast("Close! +5 XP", { icon: "🤏" });
      }
    }
  };

  const handleSubmitAnswer = (e) => {
    e.preventDefault();
    if (!question || !currentAnswer) return;
    handleSubmissionLogic((config) =>
      axios.post(`${BASE_URL}/api/practice/submit`, { questionId: question.id, answerText: currentAnswer }, config)
    );
  };

  const confirmGetAnswer = () => {
    setOpenPopover(false);
    if (!question) return;
    setLoadingAnswer(true);
    handleSubmissionLogic((config) =>
      axios.post(`${BASE_URL}/api/practice/get-answer`, { questionId: question.id }, config)
    );
  };

  const handleGetHint = async () => {
    if (!question) return;
    setLoadingHint(true);
    setHint(null);
    setError(null);
    try {
      const res = await axios.post(`${BASE_URL}/api/ai/get-hint`, { questionId: question.id }, getAuthHeaders());
      setHint(res.data);
    } catch (err) {
      console.error("Error getting hint:", err);
      setError("Failed to get a hint. Please try again.");
    } finally {
      setLoadingHint(false);
    }
  };

  // --- Filtering ---
  const filteredHistory = useMemo(() => {
    if (!searchTerm) return allHistory;
    const q = searchTerm.toLowerCase();
    return allHistory.filter((item) => {
      const fields = [item.questionText, item.subject, item.topic, item.difficulty, item.answerText].filter(Boolean).map((x) => String(x).toLowerCase());
      return fields.some((f) => f.includes(q));
    });
  }, [allHistory, searchTerm]);

  const visibleHistory = useMemo(() => filteredHistory.slice(0, itemsToShow), [filteredHistory, itemsToShow]);

  return (
    <section className="relative isolate -mx-4 md:-mx-6 lg:-mx-8 px-4 md:px-6 lg:px-8 pb-10 min-h-[calc(100vh-4rem)] overflow-x-hidden">

      {/* Styles & Animations */}
      <style>{`
        .custom-scroll::-webkit-scrollbar { width: 8px; height: 8px; }
        .custom-scroll::-webkit-scrollbar-track { background: transparent; }
        .custom-scroll::-webkit-scrollbar-thumb { background-color: #cbd5e1; border-radius: 4px; }
        .custom-scroll::-webkit-scrollbar-thumb:hover { background-color: #94a3b8; }
        .dark .custom-scroll::-webkit-scrollbar-track { background: #111827; }
        .dark .custom-scroll::-webkit-scrollbar-thumb { background-color: #374151; border: 2px solid #111827; border-radius: 4px; }
        .dark .custom-scroll::-webkit-scrollbar-thumb:hover { background-color: #4b5563; }
        
        @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
        @keyframes slideUpFade { from { opacity: 0; transform: translateY(20px); } to { opacity: 1; transform: translateY(0); } }
        @keyframes scaleIn { from { opacity: 0; transform: scale(0.95); } to { opacity: 1; transform: scale(1); } }
        @keyframes pulseSoft { 0%, 100% { opacity: 1; } 50% { opacity: 0.7; } }
        
        .animate-fade-in { animation: fadeIn 0.6s ease-out forwards; }
        .animate-slide-up { animation: slideUpFade 0.5s cubic-bezier(0.16, 1, 0.3, 1) forwards; }
        .animate-pop-in { animation: scaleIn 0.3s cubic-bezier(0.34, 1.56, 0.64, 1) forwards; }
        .animate-fade-in-scale { animation: scaleIn 0.4s ease-out forwards; }
        .animate-pulse-soft { animation: pulseSoft 2s infinite ease-in-out; }

        .practice-safe-wrap,
        .practice-safe-wrap * {
          overflow-wrap: anywhere;
          word-break: break-word;
        }
        .practice-safe-wrap pre {
          white-space: pre-wrap !important;
          overflow-x: hidden !important;
        }
        .practice-safe-wrap code {
          white-space: pre-wrap !important;
        }
      `}</style>

      {/* Background Layer */}
      <div className="absolute inset-0 -z-10 bg-gradient-to-b from-blue-50 via-sky-100 to-blue-100 dark:from-gray-900 dark:via-blue-950 dark:to-gray-900 transition-colors duration-700" />
      <div className="pointer-events-none absolute -top-10 right-[8%] h-64 w-64 rounded-full bg-sky-300/30 dark:bg-sky-600/30 blur-3xl animate-pulse-soft" />
      <div className="pointer-events-none absolute top-36 -left-10 h-72 w-72 rounded-full bg-blue-300/25 dark:bg-blue-700/25 blur-3xl animate-pulse-soft" style={{ animationDelay: '1s' }} />

      <div className="mt-6 page has-fixed-navbar space-y-8 max-w-7xl mx-auto overflow-x-hidden">

        {/* --- Active Study Plan Context Banner --- */}
        {activeContext && (
          <div className="bg-white/90 dark:bg-gray-800/90 backdrop-blur-md border border-indigo-100 dark:border-indigo-900 rounded-xl p-4 shadow-sm animate-slide-up relative overflow-hidden">
            <div className="absolute left-0 top-0 bottom-0 w-1 bg-indigo-500 rounded-l-xl" />
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
              <div className="flex items-center gap-4">
                <div className="p-2.5 bg-indigo-50 dark:bg-indigo-900/30 rounded-lg text-indigo-600 dark:text-indigo-400">
                  <BookOpenIcon className="h-6 w-6" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <Typography variant="h6" className="text-gray-900 dark:text-white font-bold">
                      {activeContext.planTitle}
                    </Typography>
                    <Chip size="sm" value={`Day ${activeContext.currentDay} of ${activeContext.totalDays}`} className="bg-indigo-50 text-indigo-900 dark:bg-indigo-900/50 dark:text-indigo-200" />
                  </div>
                  <div className="flex items-center gap-2 mt-1">
                    <div className="w-24 h-1.5 bg-gray-100 dark:bg-gray-700 rounded-full overflow-hidden">
                      <div className="h-full bg-indigo-500 rounded-full" style={{ width: `${activeContext.progress}%` }} />
                    </div>
                    <Typography variant="small" className="text-gray-500 text-xs">
                      {activeContext.progress}% Complete
                    </Typography>
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-3">
                <div className="hidden md:flex -space-x-2">
                  {activeContext.todayItems.map(item => (
                    <Tooltip content={item.title} key={item.itemId}>
                      <div className={`w-8 h-8 rounded-full border-2 border-white dark:border-gray-800 flex items-center justify-center text-xs font-bold
                                        ${item.isCompleted ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                        {item.isCompleted ? <CheckCircleIcon className="h-5 w-5" /> : (item.type === 'VIDEO' ? '📺' : '📝')}
                      </div>
                    </Tooltip>
                  ))}
                </div>
                <Button
                  size="sm"
                  className="bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg px-4 flex items-center gap-2"
                  onClick={() => {
                    const next = activeContext.nextPractice;
                    if (next) {
                      setSubject(next.subject);
                      setTopic(next.topic);
                      setDifficulty(next.difficulty);
                      toast.success(`Loaded from plan: ${next.topic}`);
                    }
                  }}
                >
                  Continue Plan <ChevronRightIcon className="h-3 w-3" />
                </Button>
              </div>
            </div>
          </div>
        )}

        <div className="flex flex-col gap-8">

          {/* Generator & Coach Area (Full Width) */}
          <div className="space-y-6">

            {/* Generator Card */}
            <Card className="overflow-visible relative z-20 border border-blue-100/60 dark:border-gray-700 bg-white/80 dark:bg-gray-900/80 backdrop-blur-md shadow-sm animate-slide-up">
              <CardHeader floated={false} shadow={false} className="rounded-t-xl bg-gradient-to-r from-blue-600 to-blue-400 px-6 py-4 m-0 flex justify-between items-center">
                <div className="flex items-center gap-3">
                  <div className="p-2 bg-white/20 rounded-lg shadow-inner">
                    <SparklesIcon className="h-6 w-6 text-white animate-pulse" />
                  </div>
                  <div>
                    <Typography variant="h5" color="white" className="font-bold tracking-tight">Question Generator</Typography>
                    <Typography variant="small" color="white" className="opacity-90 font-normal">Select a topic and generate a practice question.</Typography>
                  </div>
                </div>
                {streak > 1 && (
                  <div className="hidden sm:flex items-center gap-2 px-3 py-1 bg-white/20 backdrop-blur-sm rounded-full border border-white/30">
                    <FireIcon className="h-5 w-5 text-orange-300 animate-pulse" />
                    <span className="text-white font-bold text-sm">{streak} Streak!</span>
                  </div>
                )}
              </CardHeader>

              <CardBody className="p-4 md:p-8 overflow-visible">
                <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
                  {/* Subject Input */}
                  <div className="w-full">
                    <Input
                      size="lg"
                      label="Subject"
                      placeholder="e.g. Java, DBMS"
                      value={subject}
                      onChange={(e) => setSubject(e.target.value)}
                      crossOrigin="anonymous"
                      color="blue"
                      className="!text-blue-gray-900 dark:!text-white !bg-white dark:!bg-gray-800 !border-blue-gray-200 focus:!border-blue-500 placeholder:opacity-50"
                      labelProps={{ className: "!text-blue-gray-500 dark:!text-gray-400" }}
                    />
                  </div>

                  {/* Topic Input */}
                  <div className="w-full">
                    <Input
                      size="lg"
                      label="Topic"
                      placeholder="e.g. Inheritance"
                      value={topic}
                      onChange={(e) => setTopic(e.target.value)}
                      crossOrigin="anonymous"
                      color="blue"
                      className="!text-blue-gray-900 dark:!text-white !bg-white dark:!bg-gray-800 !border-blue-gray-200 focus:!border-blue-500 placeholder:opacity-50"
                      labelProps={{ className: "!text-blue-gray-500 dark:!text-gray-400" }}
                    />
                  </div>

                  {/* Difficulty Select */}
                  <div className="w-full relative">
                    <Select
                      size="lg"
                      label="Difficulty"
                      value={difficulty}
                      onChange={(val) => setDifficulty(val)}
                      color="blue"
                      className="!text-blue-gray-900 dark:!text-white !bg-white dark:!bg-gray-800 !border-blue-gray-200 focus:!border-blue-500"
                      labelProps={{ className: "!text-blue-gray-500 dark:!text-gray-400" }}
                      menuProps={{ className: "p-2 bg-white dark:bg-gray-900 border border-blue-gray-50 dark:border-gray-800 shadow-lg rounded-xl z-[9999]" }}
                    >
                      {["Beginner", "Intermediate", "Advanced", "High School", "Graduation", "Post Graduation", "Research", "Hard"].map(d => (
                        <Option key={d} value={d} className="mb-1 rounded-lg hover:bg-blue-50 dark:hover:bg-gray-800">{d}</Option>
                      ))}
                    </Select>
                  </div>
                </div>

                {/* Recommendation Cards */}
                {recommendations && recommendations.length > 0 && (
                  <div className="mt-6">
                    <Typography variant="small" className="text-gray-500 mb-2 font-medium ml-1">Recommended for you</Typography>
                    <div className="flex gap-3 overflow-x-auto pb-4 px-1 pr-4 -mx-1 custom-scroll snap-x">
                      {recommendations.map((rec, idx) => (
                        <RecommendationCard
                          key={idx}
                          recommendation={rec}
                          compact
                          onAction={(r) => {
                            setSubject(r.subject || r.topic || "");
                            setTopic(r.topic || "");
                            setDifficulty(r.suggestedDifficulty || r.currentDifficulty || "Beginner");
                          }}
                        />
                      ))}
                    </div>
                  </div>
                )}

                {error && (
                  <Alert color="red" className="mt-4 rounded-xl bg-red-50 text-red-900 border border-red-200" icon={<InformationCircleIcon className="mt-px h-6 w-6" />}>
                    <Typography className="font-medium text-red-900">{error}</Typography>
                  </Alert>
                )}

                <div className="mt-8 flex justify-end">
                  <div className="flex w-full sm:w-auto flex-col sm:flex-row items-stretch sm:items-center gap-3 sm:gap-4">
                    {/* Circular Success Predictor */}
                    {prediction && (
                      <Tooltip content={`Win Probability: ${(prediction.winProbability * 100).toFixed(0)}% (${prediction.confidence} Confidence)`}>
                        <div className="flex items-center gap-2 px-3 py-2 min-h-[56px] bg-gray-50 dark:bg-gray-800 rounded-full border border-gray-200 dark:border-gray-700 animate-fade-in">
                          <CircularGauge value={prediction.winProbability * 100} loading={loadingPrediction} />
                          <div className="flex h-full items-center">
                            <span className="text-[10px] font-bold text-gray-500 uppercase tracking-wide">Success Chance</span>
                          </div>
                        </div>
                      </Tooltip>
                    )}

                    <Button
                      onClick={handleGenerateQuestion}
                      disabled={generating || submitting}
                      className="w-full sm:w-auto px-8 py-3 rounded-xl bg-gradient-to-r from-blue-600 to-blue-500 shadow-blue-500/20 hover:shadow-blue-500/40"
                    >
                      {generating ? "Crafting..." : "Generate Question"}
                    </Button>
                  </div>
                </div>
              </CardBody>
            </Card>

            {/* Coach Card */}
            <AICoachCard />

            {/* Question & Feedback Area (Existing implementation optimized) */}
            {question && (
              <div className="space-y-6 animate-slide-up">
                <Card className="border border-blue-gray-100 dark:border-gray-700 shadow-sm bg-white/80 dark:bg-gray-900/80 backdrop-blur-md">
                  <CardBody className="p-6 md:p-8">
                    <div className="flex flex-wrap justify-between items-start gap-2 mb-4">
                      <Chip value={question.difficulty} size="sm" variant="ghost" className="rounded-full bg-blue-50 text-blue-900" />
                      <Typography variant="small" className="text-gray-400">ID: {question.id}</Typography>
                    </div>
                    <div className="mb-4 prose dark:prose-invert max-w-none font-bold dark:text-gray-100 leading-snug text-xl practice-safe-wrap">
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>
                        {formatMarkdownText(question.questionText)}
                      </ReactMarkdown>
                    </div>

                    <Textarea
                      rows={textareaRows}
                      placeholder="Type your answer here..."
                      className="!text-blue-gray-900 dark:!text-white !bg-white dark:!bg-gray-800 !border-blue-gray-200 focus:!border-blue-500 text-lg"
                      value={currentAnswer}
                      onChange={handleAnswerChange}
                      disabled={submitting}
                    />

                    <div className="mt-6 flex flex-wrap gap-4 justify-between items-center">
                      <div className="flex gap-2">
                        <Button size="sm" variant="text" color="blue-gray" onClick={handleGetHint} disabled={loadingHint || submitting}>
                          {loadingHint ? "Loading..." : "💡 Get Hint"}
                        </Button>
                      </div>
                      <div className="flex gap-3">
                        <Button variant="outlined" color="red" onClick={() => setOpenPopover(true)} disabled={submitting}>
                          Give Up
                        </Button>
                        <Button color="green" onClick={handleSubmitAnswer} disabled={!currentAnswer.trim() || submitting}>
                          {submitting ? <Spinner className="h-4 w-4" /> : "Submit Answer"}
                        </Button>
                      </div>
                    </div>

                    {hint && (
                      <Alert className="mt-4 bg-amber-50 dark:bg-amber-900/20 text-amber-900 dark:text-amber-200 border border-amber-200 dark:border-amber-900/50 rounded-xl animate-fade-in">
                        <div className="flex gap-3">
                          <InformationCircleIcon className="h-6 w-6 text-amber-500 dark:text-amber-400" />
                          <div>
                            <Typography className="font-bold text-sm">Hint</Typography>
                            <Typography className="text-sm opacity-90">{hint}</Typography>
                          </div>
                        </div>
                      </Alert>
                    )}
                  </CardBody>
                </Card>

                {/* Feedback Area */}
                {(feedback || isPolling) && (
                  <Card className={`border shadow-sm animate-slide-up bg-white dark:bg-gray-900 ${
                    feedback?.evaluationStatus === 'CORRECT' ? 'border-green-200 dark:border-green-900/50' :
                    feedback?.evaluationStatus === 'CLOSE' ? 'border-orange-200 dark:border-orange-900/50' :
                    feedback?.evaluationStatus === 'REVEALED' ? 'border-blue-200 dark:border-blue-900/50' : 'border-red-200 dark:border-red-900/50'
                  }`}>
                    <CardBody className="p-6">
                      {isPolling ? (
                        <div className="flex flex-col items-center justify-center py-8">
                          <Spinner className="h-8 w-8 text-blue-500 mb-2" />
                          <Typography>Evaluating your answer...</Typography>
                        </div>
                      ) : (
                        <>
                          <DynamicFeedbackTitle status={feedback.evaluationStatus} />
                          <div className="prose dark:prose-invert max-w-none text-gray-700 dark:text-gray-300 practice-safe-wrap">
                            <ReactMarkdown remarkPlugins={[remarkGfm]}>
                              {formatMarkdownText(feedback.feedback)}
                            </ReactMarkdown>
                          </div>
                          {feedback.evaluationStatus !== "CORRECT" && (
                            <div className="mt-6 p-4 bg-gray-50 dark:bg-gray-800 rounded-xl border border-gray-100 dark:border-gray-700">
                              <Typography variant="small" className="font-bold text-gray-500 uppercase tracking-wide mb-2">
                                Correct Answer
                              </Typography>
                              <div className="prose dark:prose-invert max-w-none text-gray-900 dark:text-gray-100 practice-safe-wrap">
                                {feedback.answerText ? (
                                  <ReactMarkdown remarkPlugins={[remarkGfm]}>
                                    {formatMarkdownText(feedback.answerText)}
                                  </ReactMarkdown>
                                ) : (
                                  <Typography className="font-medium">
                                    See explanation above
                                  </Typography>
                                )}
                              </div>
                            </div>
                          )}
                        </>
                      )}
                    </CardBody>
                  </Card>
                )}
              </div>
            )}

            {/* Question & Feedback Area (Full Width) */}
            {question && (
              <div className="space-y-6 animate-slide-up">
                {/* ... existing question card code ... */}
              </div>
            )}

          </div>

          {/* Bottom Section: Recent Activity (Refactored to Grid) */}
          <div className="space-y-6">
            <div>
              <Typography variant="h5" color="blue-gray" className="mb-1 dark:text-white">
                Recent Activity
              </Typography>
              <Typography variant="small" className="font-normal text-blue-gray-600 dark:text-gray-400">
                Review your latest practice sessions
              </Typography>
            </div>

            {loadingHistory ? (
              <div className="flex justify-center py-12">
                <Spinner className="h-8 w-8 text-blue-gray-900 dark:text-white" />
              </div>
            ) : allHistory.length === 0 ? (
              <Card className="border border-blue-gray-100 dark:border-gray-700 shadow-sm bg-white/80 dark:bg-gray-900/80 backdrop-blur-md">
                <CardBody className="text-center py-12">
                  <BookOpenIcon className="h-12 w-12 text-blue-gray-200 dark:text-gray-700 mx-auto mb-4" />
                  <Typography color="blue-gray" variant="h6" className="dark:text-white">
                    No activity yet
                  </Typography>
                  <Typography color="gray" className="font-normal mt-1 dark:text-gray-400">
                    Your practice history will appear here.
                  </Typography>
                </CardBody>
              </Card>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {visibleHistory.map((item) => (
                  <Card
                    key={item.id}
                    onClick={() => setSelectedHistory(item)}
                    className="cursor-pointer border border-blue-gray-100 dark:border-gray-700 hover:shadow-md transition-all group bg-white/80 dark:bg-gray-900/80 backdrop-blur-md h-[220px] sm:h-[230px]"
                  >
                    <CardBody className="p-5 relative h-full flex flex-col">
                      <div className="flex flex-wrap justify-between items-start gap-2 mb-3">
                        {getStatusChip(item.evaluationStatus)}
                        <span className="text-[10px] text-gray-400">
                          {new Date(item.submittedAt).toLocaleDateString()}
                        </span>
                      </div>
                      <Typography variant="h6" color="blue-gray" className="mb-1 group-hover:text-blue-500 transition-colors dark:text-white break-words practice-safe-wrap overflow-hidden leading-snug" style={{ display: "-webkit-box", WebkitLineClamp: 3, WebkitBoxOrient: "vertical" }}>
                        {item.questionText}
                      </Typography>
                      <Typography className="text-xs text-gray-500 break-words practice-safe-wrap overflow-hidden" style={{ display: "-webkit-box", WebkitLineClamp: 1, WebkitBoxOrient: "vertical" }}>
                        {item.subject} • {item.topic}
                      </Typography>
                      <div className="mt-auto pt-4 border-t border-blue-gray-50 dark:border-gray-800 flex flex-wrap justify-between items-center gap-2 text-xs">
                        <Typography variant="small" className="text-gray-400 font-medium">Click to view feedback</Typography>
                        <ChevronRightIcon className="h-4 w-4 text-gray-400 group-hover:translate-x-1 transition-transform" />
                      </div>
                    </CardBody>
                  </Card>
                ))}
              </div>
            )}

            {visibleHistory.length < allHistory.length && (
              <div className="flex justify-center mt-6">
                <Button
                  variant="outlined"
                  size="sm"
                  className="rounded-full px-8"
                  onClick={() => setItemsToShow(n => n + 6)}
                >
                  Load More Activity
                </Button>
              </div>
            )}

            {/* Empty State */}
            {!question && !generating && (
              <div className="flex flex-col items-center justify-center py-12 px-4 text-center opacity-60">
                <div className="w-48 h-48 bg-blue-50 dark:bg-gray-800 rounded-full flex items-center justify-center mb-6">
                  <SparklesIcon className="h-24 w-24 text-blue-200 dark:text-gray-700" />
                </div>
                <Typography variant="h5" color="blue-gray" className="mb-2 font-bold">Ready to Practice?</Typography>
                <Typography className="max-w-md text-gray-500">
                  Select a topic from your study plan or enter a custom one above to start your practice session.
                </Typography>
              </div>
            )}
          </div>
        </div>

        {/* History Detail Dialog (unchanged logic) */}
        <Dialog open={!!selectedHistory} handler={() => setSelectedHistory(null)} size="lg" className="bg-gray-900 dark:bg-gray-950 border border-gray-800 shadow-2xl outline-none w-[95vw] max-w-3xl h-[calc(100dvh-2rem)] sm:h-auto sm:max-h-[90vh] flex flex-col overflow-hidden">
          <DialogHeader className="text-gray-100 font-bold border-b border-gray-800 p-4 sm:p-6 flex justify-between items-start relative">
            <Typography variant="h6" className="font-bold">
              Practice Details
            </Typography>
            <button type="button" onClick={() => setSelectedHistory(null)} className="absolute top-4 right-4 p-2 text-gray-400 hover:text-white shrink-0 z-50">
              <XMarkIcon className="h-6 w-6" />
            </button>
          </DialogHeader>
          <DialogBody className="flex-1 min-h-0 overflow-y-auto overscroll-contain [webkit-overflow-scrolling:touch] bg-gray-900/40 p-4 sm:p-6 pb-[calc(env(safe-area-inset-bottom)+6rem)] sm:pb-6 custom-scroll">
            {selectedHistory && (
              <div className="space-y-4">
                <div className="p-5 bg-gray-800/40 border border-gray-700/30 rounded-2xl shadow-inner h-40 sm:h-48 flex flex-col">
                  <Typography className="font-black text-[10px] uppercase text-gray-500 tracking-widest mb-3">Question</Typography>
                  <div className="prose prose-invert max-w-none text-gray-200 practice-safe-wrap [&_pre]:whitespace-pre-wrap [&_pre]:break-words [&_code]:break-words flex-1 min-h-0 overflow-y-auto overscroll-contain [webkit-overflow-scrolling:touch] custom-scroll pr-1">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>
                      {formatMarkdownText(selectedHistory.questionText)}
                    </ReactMarkdown>
                  </div>
                </div>
                <div className="p-5 bg-gray-800/40 border border-gray-700/30 rounded-2xl shadow-inner h-56 sm:h-64 flex flex-col">
                  <Typography className="font-black text-[10px] uppercase text-gray-500 tracking-widest mb-3">Your Answer</Typography>
                  <div className="prose prose-invert max-w-none text-gray-200 [&_pre]:whitespace-pre-wrap [&_pre]:break-words [&_code]:break-words flex-1 min-h-0 overflow-y-auto overscroll-contain [webkit-overflow-scrolling:touch] custom-scroll pr-1">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>
                      {selectedHistory.answerText}
                    </ReactMarkdown>
                  </div>
                </div>
                <div className="p-5 bg-blue-900/10 border border-blue-800/20 rounded-2xl shadow-inner h-56 sm:h-64 flex flex-col">
                  <div className="flex items-center gap-2 mb-3">
                    <SparklesIcon className="h-4 w-4 text-blue-400" />
                    <Typography className="font-black text-[10px] uppercase text-blue-400 tracking-widest">Feedback</Typography>
                  </div>
                  <div className="prose prose-invert max-w-none text-gray-300 [&_pre]:whitespace-pre-wrap [&_pre]:break-words [&_code]:break-words flex-1 min-h-0 overflow-y-auto overscroll-contain [webkit-overflow-scrolling:touch] custom-scroll pr-1">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{selectedHistory.feedback}</ReactMarkdown>
                  </div>
                </div>
              </div>
            )}
          </DialogBody>
          <DialogFooter className="shrink-0 border-t border-gray-800 p-4 pb-[calc(env(safe-area-inset-bottom)+1rem)] sm:pb-4">
            <Button variant="text" color="blue-gray" onClick={() => setSelectedHistory(null)} className="text-gray-400 hover:text-white transition-colors">
              Close
            </Button>
          </DialogFooter>
        </Dialog>

        {/* Give Up Popover (unchanged logic) */}
        <Dialog open={openPopover} handler={() => setOpenPopover(false)} size="xs" className="bg-gray-900 border border-gray-800 shadow-xl">
          <DialogHeader className="text-gray-100 text-lg font-bold">Reveal Answer?</DialogHeader>
          <DialogBody className="text-gray-400 py-2 px-6">
            Are you sure? This session will not count towards your daily streak.
          </DialogBody>
          <DialogFooter className="gap-3">
            <Button variant="text" color="white" onClick={() => setOpenPopover(false)} className="text-gray-400 hover:text-white">
              Cancel
            </Button>
            <Button color="red" variant="gradient" onClick={confirmGetAnswer} className="rounded-xl shadow-red-500/20">
              Reveal Answer
            </Button>
          </DialogFooter>
        </Dialog>

      </div>
    </section>
  );
}

export default Practice;
